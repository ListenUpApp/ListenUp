package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.Hardcover_shelf_entries
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/** Whether a book Hardcover's Want to Read put on a shelf is still Hardcover's to take off. */
enum class HardcoverShelfEntryState {
    /** Hardcover put it there, and takes it off when it leaves Want to Read. */
    ON_SHELF,

    /** The user took it off by hand: it isn't put back while it stays on Want to Read. */
    USER_REMOVED,
}

/** One book Hardcover's Want to Read put on [shelfId], from Hardcover shelf entry [hcUserBookId]. */
data class HardcoverShelfEntryRecord(
    val bookId: String,
    val shelfId: String,
    val hcUserBookId: Long,
    val state: HardcoverShelfEntryState,
)

/** A user's two Want to Read shelves, either of which may since have been deleted. */
data class WantToReadTargets(
    val starterShelfId: String?,
    val hardcoverShelfId: String?,
)

/** What placing one Want to Read book needs, read in one transaction: its record, and whether it is on the target. */
data class WantToReadSighting(
    val record: HardcoverShelfEntryRecord?,
    val isOnTarget: Boolean,
)

/**
 * The persistence of Hardcover's Want to Read (#1539): which books Hardcover put on a shelf
 * (`hardcover_shelf_entries`), which of them the user took off by hand, when a pull last saw each on Want
 * to Read, and the user's two target shelves (`users.starter_shelf_id`, `users.hardcover_shelf_id`).
 * It never writes a shelf: [HardcoverWantToRead] does, through the shelf repositories, so every shelf
 * change syncs.
 */
class HardcoverShelfEntryStore(
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
) {
    private val queries get() = sql.hardcoverShelfEntriesQueries

    /** [userId]'s record for [bookId], or null when Hardcover never put it on a shelf. */
    suspend fun recordFor(
        userId: String,
        bookId: String,
    ): HardcoverShelfEntryRecord? =
        suspendTransaction(sql) {
            queries.selectForBook(userId, bookId).executeAsOneOrNull()?.toRecord()
        }

    /** Every record of [userId], by book. */
    suspend fun records(userId: String): List<HardcoverShelfEntryRecord> =
        suspendTransaction(sql) { queries.selectForUser(userId).executeAsList().map { it.toRecord() } }

    /** The records that came from Hardcover shelf entries [hcUserBookIds]. */
    suspend fun recordsFromEntries(
        userId: String,
        hcUserBookIds: List<Long>,
    ): List<HardcoverShelfEntryRecord> {
        if (hcUserBookIds.isEmpty()) return emptyList()
        return suspendTransaction(sql) {
            queries.selectForHcUserBooks(userId, hcUserBookIds).executeAsList().map { it.toRecord() }
        }
    }

    /** The records a pull last saw on Want to Read before [since]: what a full pull that started then never saw. */
    suspend fun notSeenSince(
        userId: String,
        since: Long,
    ): List<HardcoverShelfEntryRecord> =
        suspendTransaction(sql) {
            queries.selectNotSeenSince(userId, since).executeAsList().map { it.toRecord() }
        }

    /**
     * [bookId]'s record and whether it is live on [targetShelfId], read together, so a hand removal can't
     * land between the two reads.
     */
    suspend fun sighting(
        userId: String,
        bookId: String,
        targetShelfId: String?,
    ): WantToReadSighting =
        suspendTransaction(sql) {
            WantToReadSighting(
                record = queries.selectForBook(userId, bookId).executeAsOneOrNull()?.toRecord(),
                isOnTarget = targetShelfId != null && liveOn(targetShelfId, bookId),
            )
        }

    /** Whether [bookId] is live on [shelfId]. */
    suspend fun isOnShelf(
        shelfId: String,
        bookId: String,
    ): Boolean = suspendTransaction(sql) { liveOn(shelfId, bookId) }

    /** Hardcover puts [bookId] on [shelfId], from shelf entry [hcUserBookId], seen on Want to Read at [seenAt]. */
    suspend fun putOnShelf(
        userId: String,
        bookId: String,
        shelfId: String,
        hcUserBookId: Long,
        seenAt: Long,
    ) {
        suspendTransaction(sql) {
            queries.putOnShelf(
                user_id = userId,
                book_id = bookId,
                shelf_id = shelfId,
                hc_user_book_id = hcUserBookId,
                seen_at = seenAt,
                updated_at = clock.now().toEpochMilliseconds(),
            )
        }
    }

    /** A pull saw [bookId]'s entry ([hcUserBookId]) on Want to Read at [seenAt]. */
    suspend fun markSeen(
        userId: String,
        bookId: String,
        hcUserBookId: Long,
        seenAt: Long,
    ) {
        suspendTransaction(sql) {
            queries.markSeen(hc_user_book_id = hcUserBookId, seen_at = seenAt, user_id = userId, book_id = bookId)
        }
    }

    /**
     * The user took [bookId] off [shelfId] by hand. If Hardcover put it there, it now stays off while it
     * remains on Want to Read. Called only from the user's own removal (`ShelfServiceImpl`), never from
     * Hardcover's.
     */
    suspend fun markRemovedByHand(
        userId: String,
        shelfId: String,
        bookId: String,
    ) {
        suspendTransaction(sql) {
            queries.markRemovedByHand(
                updated_at = clock.now().toEpochMilliseconds(),
                user_id = userId,
                shelf_id = shelfId,
                book_id = bookId,
            )
        }
    }

    /** Drops [bookId]'s record. */
    suspend fun forget(
        userId: String,
        bookId: String,
    ) {
        suspendTransaction(sql) { queries.deleteForBook(userId, bookId) }
    }

    /** Drops every record of [userId]. */
    suspend fun forgetAll(userId: String) {
        suspendTransaction(sql) { queries.deleteForUser(userId) }
    }

    /** [userId]'s two Want to Read shelves, or null when there is no such user. */
    suspend fun targets(userId: String): WantToReadTargets? =
        suspendTransaction(sql) { sql.usersQueries.selectShelfTargets(userId).executeAsOneOrNull() }
            ?.let { WantToReadTargets(it.starter_shelf_id, it.hardcover_shelf_id) }

    /** The pull made [shelfId] as [userId]'s Want to Read shelf. */
    suspend fun rememberHardcoverShelf(
        userId: String,
        shelfId: String,
    ) {
        suspendTransaction(sql) { sql.usersQueries.setHardcoverShelf(hardcover_shelf_id = shelfId, id = userId) }
    }

    private fun liveOn(
        shelfId: String,
        bookId: String,
    ): Boolean = sql.shelfBooksQueries.selectLiveByShelfAndBook(shelfId, bookId).executeAsOneOrNull() != null

    private fun Hardcover_shelf_entries.toRecord(): HardcoverShelfEntryRecord =
        HardcoverShelfEntryRecord(
            bookId = book_id,
            shelfId = shelf_id,
            hcUserBookId = hc_user_book_id,
            state = HardcoverShelfEntryState.valueOf(state),
        )
}
