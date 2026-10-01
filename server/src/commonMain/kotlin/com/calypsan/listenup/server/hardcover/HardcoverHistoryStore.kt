package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverHistory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.SelectUnsentHistory
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

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

/** Where the offer stands: `hardcover_history.state`. */
internal enum class HardcoverHistoryState {
    /** The card shows. */
    OFFERED,

    /** "Not now": the card is gone; the quiet row shows while history is unsent. */
    DECLINED,

    /** HISTORY rows are queued. */
    SENDING,

    /** No HISTORY row can still run: sent, skipped, or waiting for a match. */
    DONE,

    /** The Done card was dismissed. */
    DISMISSED,
}

/** A stored state this build doesn't know reads as DISMISSED: the quietest — no card, the row only for unsent history. */
internal fun hardcoverHistoryState(name: String): HardcoverHistoryState =
    HardcoverHistoryState.entries.firstOrNull { it.name == name } ?: HardcoverHistoryState.DISMISSED

/**
 * What [userId]'s Connected says about their earlier books, for a connection that began at [connectedAt]:
 * - SENDING: [HardcoverHistory.Sending], counting a book as sent once none of its HISTORY rows wait;
 * - unsent history: the card while OFFERED, else the quiet row — including after a reconnect that turned
 *   up history not yet sent, and when history appeared after connecting (no row);
 * - DONE: what the send came to, with the books still waiting for a match;
 * - anything else: nothing.
 */
internal suspend fun ListenUpDatabase.hardcoverHistory(
    userId: String,
    connectedAt: Long,
): HardcoverHistory = suspendTransaction(this) { historyOf(userId, connectedAt) }

private fun ListenUpDatabase.historyOf(
    userId: String,
    connectedAt: Long,
): HardcoverHistory {
    val queries = hardcoverHistoryQueries
    val row = queries.selectHistory(userId).executeAsOneOrNull()
    val state = row?.state?.let(::hardcoverHistoryState)
    val total = row?.total_books?.toInt() ?: 0
    if (state == HardcoverHistoryState.SENDING) {
        val waiting = queries.countBooksWithHistoryRows(userId).executeAsOne().toInt()
        return HardcoverHistory.Sending(sentBooks = (total - waiting).coerceIn(0, total), totalBooks = total)
    }
    val unsent = queries.countUnsentHistoryBooks(userId, connectedAt).executeAsOne().toInt()
    return when {
        unsent > 0 && state == HardcoverHistoryState.OFFERED -> {
            HardcoverHistory.Offer(bookCount = unsent)
        }

        unsent > 0 -> {
            HardcoverHistory.Available(bookCount = unsent)
        }

        state == HardcoverHistoryState.DONE -> {
            val parked = queries.countParkedHistoryBooks(userId).executeAsOne().toInt()
            HardcoverHistory.Done(sentBooks = (total - parked).coerceAtLeast(0), needsMatchBooks = parked)
        }

        else -> {
            HardcoverHistory.None
        }
    }
}

/**
 * The connect-time half of the offer, inside [HardcoverConnectionStore.save]'s transaction, for Hardcover
 * account [hcUserId] connected at [connectedAt]:
 * - another account's row and ledger go — what reached that account says nothing about this one;
 * - the same account keeps its row — except a send a disconnect cut short, which becomes "Not now", so
 *   what is still unsent shows as the quiet row;
 * - with no row, unsent history is offered.
 */
internal fun ListenUpDatabase.offerHardcoverHistory(
    userId: String,
    hcUserId: Long,
    connectedAt: Long,
) {
    val queries = hardcoverHistoryQueries
    val existing = queries.selectHistory(userId).executeAsOneOrNull()
    if (existing != null && existing.hc_user_id != hcUserId) {
        queries.deleteHistory(userId)
        queries.deleteHistoryReads(userId)
    } else if (existing != null) {
        val cutShort =
            hardcoverHistoryState(existing.state) == HardcoverHistoryState.SENDING &&
                queries.countHistoryRows(userId).executeAsOne() == 0L
        if (cutShort) queries.updateHistoryState(HardcoverHistoryState.DECLINED.name, connectedAt, userId)
        return
    }
    if (queries.countUnsentHistoryBooks(userId, connectedAt).executeAsOne() > 0L) {
        queries.upsertHistory(
            user_id = userId,
            hc_user_id = hcUserId,
            state = HardcoverHistoryState.OFFERED.name,
            total_books = 0L,
            updated_at = connectedAt,
        )
    }
}

/**
 * Moves a send to DONE once none of its HISTORY rows can still run — every one sent, skipped, or parked
 * for a match — and announces it. [HardcoverPushWorker] calls [settle] after each HISTORY row.
 */
class HardcoverHistoryProgress(
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
    private val activity: HardcoverSyncActivity? = null,
) {
    /** A HISTORY row of [userId]'s finished, parked or found its book gone: settle the send if that was the last. */
    suspend fun settle(userId: String) {
        val at = clock.now().toEpochMilliseconds()
        val done =
            suspendTransaction(sql) {
                val queries = sql.hardcoverHistoryQueries
                val sending =
                    queries
                        .selectHistory(userId)
                        .executeAsOneOrNull()
                        ?.let { hardcoverHistoryState(it.state) == HardcoverHistoryState.SENDING } == true
                val finished = sending && queries.countRunnableHistoryRows(userId).executeAsOne() == 0L
                if (finished) queries.updateHistoryState(HardcoverHistoryState.DONE.name, at, userId)
                finished
            }
        if (done) activity?.historyChanged(userId)
    }
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
