package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.SelectUnsentHistory
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/**
 * One read ListenUp can send as history (#1540): [readId] (a `book_reads` row) of [bookId], finished at
 * [finishedAt] (epoch ms), begun at [startedAt] — the earliest listening after the previous own read of the
 * book — or null when no listening is recorded for it.
 */
internal data class HardcoverHistoryRead(
    val readId: String,
    val bookId: String,
    val startedAt: Long?,
    val finishedAt: Long,
)

/** What became of a history read on Hardcover: the ledger's `outcome`. */
internal enum class HardcoverHistoryOutcome {
    /** ListenUp wrote it to Hardcover. */
    SENT,

    /** Hardcover already held it, so nothing was written. */
    ALREADY_THERE,
}

/**
 * [userId]'s history: their own reads finished before their connection began at [connectedAt], not yet in the
 * ledger and not waiting in the outbox, oldest first. Books removed from the library are left out.
 */
internal suspend fun ListenUpDatabase.unsentHardcoverHistory(
    userId: String,
    connectedAt: Long,
): List<HardcoverHistoryRead> =
    suspendTransaction(this) {
        hardcoverHistoryQueries.selectUnsentHistory(userId, connectedAt).executeAsList().map { it.toHistoryRead() }
    }

/** How many distinct books [unsentHardcoverHistory] holds: the number the card and the quiet row say. */
internal suspend fun ListenUpDatabase.unsentHardcoverHistoryBooks(
    userId: String,
    connectedAt: Long,
): Int = suspendTransaction(this) { hardcoverHistoryQueries.countUnsentHistoryBooks(userId, connectedAt).executeAsOne().toInt() }

/** [userId]'s own reads of [bookId] up to and including [readId] (finished at [finishedAt]). */
internal suspend fun ListenUpDatabase.ownReadsThrough(
    userId: String,
    bookId: String,
    finishedAt: Long,
    readId: String,
): Long =
    suspendTransaction(this) {
        hardcoverHistoryQueries.countOwnReadsThrough(userId, bookId, finishedAt, readId).executeAsOne()
    }

/**
 * Records [outcome] for history read [readId] in the ledger, replacing any earlier outcome. A read deleted
 * since it was queued has nothing left to be remembered by, so it records nothing.
 */
internal suspend fun ListenUpDatabase.recordHardcoverHistoryRead(
    userId: String,
    readId: String,
    outcome: HardcoverHistoryOutcome,
    at: Long,
) {
    suspendTransaction(this) {
        if (hardcoverHistoryQueries.readExists(readId).executeAsOne()) {
            hardcoverHistoryQueries.recordRead(user_id = userId, read_id = readId, outcome = outcome.name, recorded_at = at)
        }
    }
}

/**
 * A live FINISH of [bookId] reached Hardcover: its read — the newest own read finished since the
 * listen-through began at [listenThrough] — goes in the history ledger as SENT. Reconnecting the same account
 * then never offers it as history.
 */
internal suspend fun ListenUpDatabase.recordLiveFinish(
    userId: String,
    bookId: String,
    listenThrough: Long,
    at: Long,
) {
    suspendTransaction(this) {
        hardcoverHistoryQueries.latestOwnReadSince(userId, bookId, listenThrough).executeAsOneOrNull()?.let { readId ->
            hardcoverHistoryQueries.recordRead(
                user_id = userId,
                read_id = readId,
                outcome = HardcoverHistoryOutcome.SENT.name,
                recorded_at = at,
            )
        }
    }
}

internal fun SelectUnsentHistory.toHistoryRead(): HardcoverHistoryRead =
    HardcoverHistoryRead(readId = id, bookId = book_id, startedAt = derived_started_at, finishedAt = finished_at)
