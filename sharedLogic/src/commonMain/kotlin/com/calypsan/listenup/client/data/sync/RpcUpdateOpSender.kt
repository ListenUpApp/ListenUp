package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.AppResult

/**
 * The one push-side sender for entity-PATCH edits, replacing every hand-rolled
 * `<Domain>UpdateOpSender`. On drain it decodes the queued payload with the domain's serializer
 * and hands `(entityId, patch)` to [push]; the domain supplies whatever RPC call it wants —
 * clean `update(id, patch)`, a `my`-scoped call that ignores the id, or a bundled request.
 *
 * The push result is discarded on success: some update RPCs return the merged entity, but the
 * authoritative state arrives via the SSE sync engine and reconciles through the domain's
 * `SyncDomainHandler`. Hence [push]'s `WireAppResult<*>` return.
 *
 * `PlaybackPositionOpSender` and `ListeningEventOpSender` are a different family (event/position
 * semantics, not entity-PATCH) and keep their bespoke senders.
 */
internal class RpcUpdateOpSender<T : Any>(
    private val domain: EditableDomain<T>,
    private val push: suspend (entityId: String, patch: T) -> WireAppResult<*>,
) : PendingOperationSender {
    override suspend fun send(op: PendingOperation): AppResult<Unit> {
        val patch = contractJson.decodeFromString(domain.serializer, op.payload)
        return when (val result = push(op.entityId, patch)) {
            is WireAppResult.Success -> AppResult.Success(Unit)
            is WireAppResult.Failure -> AppResult.Failure(result.error)
        }
    }
}
