package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * The listener's two answers to the earlier-books offer (#1540). [send] queues every unsent history read as
 * a HISTORY row and wakes the push lane; [dismiss] is "Not now" on the card, or dismissing the Done card.
 * Every change is announced on [HardcoverSyncActivity.historyChanged], so each watching client's Connected
 * is republished with it.
 */
class HardcoverHistorySender(
    private val sql: ListenUpDatabase,
    private val clock: Clock = Clock.System,
    private val nudge: HardcoverPushNudge = HardcoverPushNudge { },
    private val activity: HardcoverSyncActivity? = null,
) {
    /**
     * Queues every history read not yet sent or queued — in one transaction, with the state moving to
     * SENDING over the books now waiting — and wakes [userId]'s lane. Nothing to send succeeds and changes
     * nothing, so a second press, or a blind retry, is safe. [HardcoverError.NotConnected] without a
     * connection; [HardcoverError.ConnectionBroken] when it needs a reconnect.
     */
    suspend fun send(userId: String): AppResult<Unit> {
        val at = clock.now().toEpochMilliseconds()
        return when (suspendTransaction(sql) { queueUnsent(userId, at) }) {
            SendResult.NOT_CONNECTED -> {
                AppResult.Failure(HardcoverError.NotConnected())
            }

            SendResult.BROKEN -> {
                AppResult.Failure(HardcoverError.ConnectionBroken(debugInfo = "sendHistory"))
            }

            SendResult.NOTHING_TO_SEND -> {
                AppResult.Success(Unit)
            }

            SendResult.QUEUED -> {
                nudge.nudge(userId)
                activity?.historyChanged(userId)
                AppResult.Success(Unit)
            }
        }
    }

    /**
     * "Not now" on the offer (the card goes; the quiet row stays while history is unsent), or dismissing
     * Done (final). Anything else is left as it is. Always succeeds.
     */
    suspend fun dismiss(userId: String): AppResult<Unit> {
        val at = clock.now().toEpochMilliseconds()
        val changed =
            suspendTransaction(sql) {
                val queries = sql.hardcoverHistoryQueries
                val next =
                    when (
                        queries
                            .selectHistory(userId)
                            .executeAsOneOrNull()
                            ?.run { hardcoverHistoryState(state) }
                    ) {
                        HardcoverHistoryState.OFFERED -> HardcoverHistoryState.DECLINED

                        HardcoverHistoryState.DONE -> HardcoverHistoryState.DISMISSED

                        HardcoverHistoryState.DECLINED,
                        HardcoverHistoryState.SENDING,
                        HardcoverHistoryState.DISMISSED,
                        null,
                        -> null
                    }
                next?.let { queries.updateHistoryState(it.name, at, userId) } != null
            }
        if (changed) activity?.historyChanged(userId)
        return AppResult.Success(Unit)
    }

    private fun queueUnsent(
        userId: String,
        at: Long,
    ): SendResult {
        val connection =
            sql.hardcoverConnectionsQueries.selectByUser(userId).executeAsOneOrNull() ?: return SendResult.NOT_CONNECTED
        if (connection.broken_reason != null) return SendResult.BROKEN
        val reads = sql.hardcoverHistoryQueries.selectUnsentHistory(userId, connection.connected_at).executeAsList()
        if (reads.isEmpty()) return SendResult.NOTHING_TO_SEND
        reads.forEach { sql.insertHistoryRow(userId, it.toHistoryRead(), at) }
        sql.hardcoverHistoryQueries.upsertHistory(
            user_id = userId,
            hc_user_id = connection.hc_user_id,
            state = HardcoverHistoryState.SENDING.name,
            total_books = sql.hardcoverHistoryQueries.countBooksWithHistoryRows(userId).executeAsOne(),
            updated_at = at,
        )
        return SendResult.QUEUED
    }

    private enum class SendResult { NOT_CONNECTED, BROKEN, NOTHING_TO_SEND, QUEUED }
}
