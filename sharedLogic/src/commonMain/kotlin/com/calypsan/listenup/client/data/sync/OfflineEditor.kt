package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.domain.repository.AuthSession

/**
 * The one repo-side helper for offline-first entity edits, replacing the copy-pasted auth-guard +
 * transaction + enqueue tail in every edit repository.
 *
 * [edit] writes the domain's optimistic Room merge ([applyLocally]) inside a transaction, then
 * enqueues a durable pending op keyed by the domain's identity, so the edit persists and replays
 * on reconnect rather than failing offline. The authoritative state still arrives via the SSE
 * sync engine. Callers pass only the two irreducible facts: which entity, and how to merge the
 * patch into Room.
 */
internal class OfflineEditor(
    private val pendingQueue: PendingOperationQueue,
    private val transactionRunner: TransactionRunner,
    private val authSession: AuthSession,
) {
    suspend fun <T : Any> edit(
        domain: EditableDomain<T>,
        entityId: String,
        patch: T,
        applyLocally: suspend () -> Unit,
    ): AppResult<Unit> {
        val ownerUserId =
            authSession.getUserId()
                ?: return AppResult.Failure(ErrorMapper.map(IllegalStateException("No signed-in user")))
        transactionRunner.atomically { applyLocally() }
        pendingQueue.enqueue(
            domainName = domain.name,
            entityId = entityId,
            opType = "update",
            payload = contractJson.encodeToString(domain.serializer, patch),
            ownerUserId = ownerUserId,
        )
        return AppResult.Success(Unit)
    }
}
