package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import com.calypsan.listenup.client.domain.repository.AuthSession

/**
 * The one repo-side helper for offline-first entity edits, replacing the copy-pasted auth-guard +
 * transaction + enqueue tail in every edit repository.
 *
 * [edit] writes the domain's optimistic Room merge ([applyLocally]) and enqueues a durable pending
 * op keyed by the domain's identity **in one transaction** — all-or-nothing, so a crash can never
 * leave a committed local edit without its outbox row (a silently lost sync). The edit therefore
 * persists and replays on reconnect rather than failing offline; the authoritative state still
 * arrives via the SSE sync engine. Callers pass only the two irreducible facts: which entity, and
 * how to merge the patch into Room. A failing local write rolls the transaction back and surfaces
 * as a typed [AppResult.Failure] — [edit] never throws (cancellation excepted).
 */
internal class OfflineEditor(
    private val pendingQueue: PendingOperationQueue,
    private val transactionRunner: TransactionRunner,
    private val authSession: AuthSession,
) {
    /**
     * Applies [applyLocally] and enqueues the encoded [patch] for [channel] in ONE
     * transaction: a crash between the two leaves no half-synced state. [op] must be
     * declared by the channel (`check`ed at the queue — a programming error, not a
     * runtime mystery).
     *
     * The outbox row write stays INSIDE the transaction (atomic with the optimistic Room
     * merge), but the enqueue SIGNAL is ticked only AFTER the transaction commits. Ticking it
     * inside the transaction (as a bare [PendingOperationQueue.enqueue] does) races the drain
     * collector: it could wake, read pre-commit state that can't yet see the new row, find
     * nothing, and strand the op until the next unrelated trigger. Passing `signal = false`
     * and calling [PendingOperationQueue.signalEnqueued] post-commit closes that window.
     */
    suspend fun <T : Any> edit(
        channel: OutboxChannel<T>,
        entityId: String,
        patch: T,
        op: OpKind = OpKind.Update,
        applyLocally: suspend () -> Unit,
    ): AppResult<Unit> {
        val ownerUserId =
            authSession.getUserId()
                ?: return AppResult.Failure(ErrorMapper.map(IllegalStateException("No signed-in user")))
        return suspendRunCatching {
            transactionRunner.atomically {
                applyLocally()
                pendingQueue.enqueue(
                    channel = channel,
                    entityId = entityId,
                    op = op,
                    payload = contractJson.encodeToString(channel.serializer, patch),
                    ownerUserId = ownerUserId,
                    signal = false,
                )
            }
        }.map { pendingQueue.signalEnqueued() }
    }
}
