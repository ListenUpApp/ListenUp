package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.Hardcover_book_links
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

private const val LINKED = "LINKED"
private const val NEEDS_MATCH = "NEEDS_MATCH"

/**
 * One user's copy of one book as Hardcover sees it (`hardcover_book_links`). [isLinked] false is
 * NEEDS_MATCH: the book's pushes are parked until the user links it, and every Hardcover id is null.
 * A listen-through is identified by its `started_at` ([LEGACY_LISTEN_THROUGH] for a book in progress
 * before listen-throughs existed).
 */
data class HardcoverBookLink(
    val userId: String,
    val bookId: String,
    val hcBookId: Long?,
    val hcEditionId: Long?,
    val method: HardcoverMatchMethod?,
    val isLinked: Boolean,
    val hcUserBookId: Long?,
    val openHcReadId: Long?,
    val openReadListenThrough: Long?,
    val suppressedListenThrough: Long?,
    val lastProgressPushedAt: Long?,
)

/**
 * `hardcover_book_links` and `hardcover_pushed_reads`: how each of a user's books is matched on
 * Hardcover, which Hardcover records ListenUp writes to, and every read ListenUp opened or continued
 * (B3's echo suppression). An automatic match never overrides an existing row; a manual one always does.
 */
class HardcoverBookLinkStore(
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
) {
    private val queries get() = sql.hardcoverBookLinksQueries

    /** [userId]'s link for [bookId], or null when the book has never been matched. */
    suspend fun linkFor(
        userId: String,
        bookId: String,
    ): HardcoverBookLink? =
        suspendTransaction(sql) { queries.selectLink(userId, bookId).executeAsOneOrNull() }?.toLink()

    /** Records an automatic [match] — or NEEDS_MATCH when null — unless the book already has a link. */
    suspend fun recordAutomaticMatch(
        userId: String,
        bookId: String,
        match: HardcoverMatch?,
    ) {
        suspendTransaction(sql) {
            queries.insertAutomatic(
                user_id = userId,
                book_id = bookId,
                hc_book_id = match?.hcBookId,
                hc_edition_id = match?.hcEditionId,
                match_method = match?.method?.name,
                match_state = if (match != null) LINKED else NEEDS_MATCH,
                updated_at = now(),
            )
        }
    }

    /**
     * Links [bookId] to Hardcover book [hcBookId] as the user chose, as edition [hcEditionId]. Keeps the
     * Hardcover shelf and read ids only when the book didn't change.
     */
    suspend fun linkManually(
        userId: String,
        bookId: String,
        hcBookId: Long,
        hcEditionId: Long?,
    ) {
        val at = now()
        suspendTransaction(sql) {
            queries.insertPlaceholder(user_id = userId, book_id = bookId, updated_at = at)
            queries.linkManually(
                hc_book_id = hcBookId,
                hc_edition_id = hcEditionId,
                updated_at = at,
                user_id = userId,
                book_id = bookId,
            )
        }
    }

    /** "Change match": back to NEEDS_MATCH, parking the book's pushes until the user picks again. */
    suspend fun unlink(
        userId: String,
        bookId: String,
    ) {
        suspendTransaction(sql) { queries.unlink(updated_at = now(), user_id = userId, book_id = bookId) }
    }

    /** ListenUp now writes to shelf entry [userBookId] and read [readId], for [listenThrough]. */
    suspend fun recordOpenRead(
        userId: String,
        bookId: String,
        userBookId: Long,
        readId: Long,
        listenThrough: Long,
    ) {
        suspendTransaction(sql) {
            queries.recordOpenRead(
                hc_user_book_id = userBookId,
                open_hc_read_id = readId,
                open_read_listen_through_started_at = listenThrough,
                updated_at = now(),
                user_id = userId,
                book_id = bookId,
            )
        }
    }

    /** The open read is finished; the next listen-through opens its own. */
    suspend fun clearOpenRead(
        userId: String,
        bookId: String,
    ) {
        suspendTransaction(sql) { queries.clearOpenRead(updated_at = now(), user_id = userId, book_id = bookId) }
    }

    /** The deletion rule: [listenThrough] pushes nothing more, and the vanished Hardcover ids are forgotten. */
    suspend fun suppress(
        userId: String,
        bookId: String,
        listenThrough: Long,
    ) {
        suspendTransaction(sql) {
            queries.suppress(
                suppressed_listen_through_started_at = listenThrough,
                updated_at = now(),
                user_id = userId,
                book_id = bookId,
            )
        }
    }

    /** A new listen-through [listenThrough] lifts a suppression of any other one. */
    suspend fun clearSuppressionUnlessFor(
        userId: String,
        bookId: String,
        listenThrough: Long,
    ) {
        suspendTransaction(sql) {
            queries.clearSuppressionUnlessFor(
                updated_at = now(),
                user_id = userId,
                book_id = bookId,
                listen_through_started_at = listenThrough,
            )
        }
    }

    /** A PROGRESS push landed at [at]; the next one waits a sitting gap. */
    suspend fun markProgressPushed(
        userId: String,
        bookId: String,
        at: Long,
    ) {
        suspendTransaction(
            sql,
        ) { queries.markProgressPushed(last_progress_pushed_at = at, user_id = userId, book_id = bookId) }
    }

    /** Remembers that Hardcover read [readId] carries ListenUp's own listening. Idempotent. */
    suspend fun recordPushedRead(
        userId: String,
        readId: Long,
        bookId: String,
    ) {
        suspendTransaction(sql) {
            sql.hardcoverPushedReadsQueries.recordPushedRead(
                user_id = userId,
                hc_read_id = readId,
                book_id = bookId,
                recorded_at = now(),
            )
        }
    }

    /** Whether Hardcover read [readId] is one ListenUp opened or continued. */
    suspend fun isPushedRead(
        userId: String,
        readId: Long,
    ): Boolean = suspendTransaction(sql) { sql.hardcoverPushedReadsQueries.isPushedRead(userId, readId).executeAsOne() }

    /** Which of [readIds] ListenUp opened or continued — the pull's echo check, one query per page. */
    suspend fun pushedReadsAmong(
        userId: String,
        readIds: Collection<Long>,
    ): Set<Long> =
        if (readIds.isEmpty()) {
            emptySet()
        } else {
            suspendTransaction(
                sql,
            ) {
                sql.hardcoverPushedReadsQueries
                    .pushedAmong(userId, readIds)
                    .executeAsList()
                    .toSet()
            }
        }

    /** Up to [limit] started, unmatched books of [userId] with ids after [after]. */
    suspend fun unlinkedStartedBooks(
        userId: String,
        after: String,
        limit: Long,
    ): List<String> = suspendTransaction(sql) { queries.unlinkedStartedBooks(userId, after, limit).executeAsList() }

    /** [userId]'s live books that need a match, newest first. */
    suspend fun booksNeedingMatch(userId: String): List<String> =
        suspendTransaction(sql) { queries.needsMatchBooks(userId).executeAsList() }

    private fun now() = clock.now().toEpochMilliseconds()

    private fun Hardcover_book_links.toLink() =
        HardcoverBookLink(
            userId = user_id,
            bookId = book_id,
            hcBookId = hc_book_id,
            hcEditionId = hc_edition_id,
            method = match_method?.let { name -> HardcoverMatchMethod.entries.firstOrNull { it.name == name } },
            isLinked = match_state == LINKED,
            hcUserBookId = hc_user_book_id,
            openHcReadId = open_hc_read_id,
            openReadListenThrough = open_read_listen_through_started_at,
            suppressedListenThrough = suppressed_listen_through_started_at,
            lastProgressPushedAt = last_progress_pushed_at,
        )
}
