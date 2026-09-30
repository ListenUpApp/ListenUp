package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * Where one user's pull stands (the pull columns of `hardcover_connections`). [cursor] is Hardcover's
 * own `updated_at` text, opaque — sent back verbatim, never parsed or compared locally.
 */
data class HardcoverPullState(
    val cursor: String?,
    val cursorId: Long?,
    val fullPullStartedAt: Long?,
    val lastFullPullAt: Long?,
)

/** One Hardcover read about to be mirrored: its id, and its finish as epoch ms. */
data class PulledRead(
    val hcReadId: Long,
    val finishedAt: Long,
)

/**
 * One shelf entry's worth of a page: the library book it resolved to, every finished read it now
 * holds that isn't ListenUp's own (possibly none), and the link to record when it was matched just now.
 */
data class PulledBook(
    val bookId: String,
    val reads: List<PulledRead>,
    val newLink: HardcoverMatch?,
)

/** A pulled row as stored. */
data class PulledReadRow(
    val bookId: String,
    val finishedAt: Long,
    val hcReadId: Long,
)

/** The id of the `book_reads` row that mirrors Hardcover read [hcReadId] for [userId]. */
internal fun pulledReadRowId(
    userId: String,
    hcReadId: Long,
): String = "hardcover:$userId:$hcReadId"

/**
 * The pull's persistence (spec B3): its cursor and full-pull state, and the `book_reads` rows with
 * `source = 'hardcover'`. A page is written and the cursor moved in ONE transaction, so a crash between
 * the two can't happen: the cursor advances only after a page commits. Only pulled rows are ever
 * deleted, and every write is idempotent — a row's id derives from its Hardcover read id, unique per
 * user — so a pull that restarts from the beginning (a reconnect resets the cursor) rewrites nothing.
 */
class HardcoverPullStore(
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
) {
    /** [userId]'s pull state, or null without a connection. */
    suspend fun pullState(userId: String): HardcoverPullState? =
        suspendTransaction(sql) { sql.hardcoverConnectionsQueries.selectPullState(userId).executeAsOneOrNull() }
            ?.let { HardcoverPullState(it.pull_cursor, it.pull_cursor_id, it.full_pull_started_at, it.last_full_pull_at) }

    /** A full pull starts at [at]: the cursor goes back to the beginning of the shelf. */
    suspend fun startFullPull(
        userId: String,
        at: Long,
    ) {
        suspendTransaction(sql) { sql.hardcoverConnectionsQueries.startFullPull(full_pull_started_at = at, user_id = userId) }
    }

    /**
     * Commits one page: each book's pulled rows become exactly [PulledBook.reads], a matched book gets
     * its link, every book is marked seen at [seenAt], and the cursor moves to ([cursor], [cursorId]).
     */
    suspend fun commitPage(
        userId: String,
        books: List<PulledBook>,
        cursor: String,
        cursorId: Long,
        seenAt: Long,
    ) {
        val now = clock.now().toEpochMilliseconds()
        suspendTransaction(sql) {
            val reads = sql.bookReadsQueries
            val links = sql.hardcoverBookLinksQueries
            books.forEach { book ->
                book.newLink?.let { match ->
                    links.insertAutomatic(
                        user_id = userId,
                        book_id = book.bookId,
                        hc_book_id = match.hcBookId,
                        hc_edition_id = match.hcEditionId,
                        match_method = match.method.name,
                        match_state = "LINKED",
                        updated_at = now,
                    )
                }
                links.markPullSeen(pull_seen_at = seenAt, user_id = userId, book_id = book.bookId)
                val keep = book.reads.map { it.hcReadId }
                if (keep.isEmpty()) {
                    reads.deletePulledForUserBook(userId, book.bookId)
                } else {
                    reads.deletePulledForUserBookExcept(userId, book.bookId, keep)
                }
                book.reads.forEach { read ->
                    reads.insertPulled(
                        id = pulledReadRowId(userId, read.hcReadId),
                        user_id = userId,
                        book_id = book.bookId,
                        finished_at = read.finishedAt,
                        created_at = now,
                        hc_read_id = read.hcReadId,
                    )
                    reads.updatePulled(book_id = book.bookId, finished_at = read.finishedAt, user_id = userId, hc_read_id = read.hcReadId)
                }
            }
            sql.hardcoverConnectionsQueries.updatePullCursor(pull_cursor = cursor, pull_cursor_id = cursorId, user_id = userId)
        }
    }

    /**
     * The full pull that started at [startedAt] reached the end of the shelf at [at]: pulled reads of
     * books whose entry it never saw are gone from Hardcover, so they go here too.
     */
    suspend fun finishFullPull(
        userId: String,
        startedAt: Long,
        at: Long,
    ) {
        suspendTransaction(sql) {
            sql.bookReadsQueries.deletePulledNotSeenSince(user_id = userId, since = startedAt)
            sql.hardcoverConnectionsQueries.finishFullPull(last_full_pull_at = at, user_id = userId)
        }
    }

    /** The next pull re-reads the whole shelf. */
    suspend fun requestFullPull(userId: String) {
        suspendTransaction(sql) { sql.hardcoverConnectionsQueries.requestFullPull(userId) }
    }

    /** [bookId]'s match changed: the reads pulled through the old match no longer belong to it. */
    suspend fun forgetPulledBook(
        userId: String,
        bookId: String,
    ) {
        suspendTransaction(sql) { sql.bookReadsQueries.deletePulledForUserBook(userId, bookId) }
    }

    /** Every pulled row of [userId], by book then Hardcover id. */
    suspend fun pulledReads(userId: String): List<PulledReadRow> =
        suspendTransaction(sql) {
            sql.bookReadsQueries.pulledForUser(userId).executeAsList().map {
                PulledReadRow(it.book_id, it.finished_at, checkNotNull(it.hc_read_id))
            }
        }
}
