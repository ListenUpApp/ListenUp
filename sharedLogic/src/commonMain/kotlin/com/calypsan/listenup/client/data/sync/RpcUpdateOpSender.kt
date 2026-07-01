package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.AppResult
import kotlinx.coroutines.CancellationException

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
        val patch =
            try {
                contractJson.decodeFromString(domain.serializer, op.payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A corrupt / schema-drifted payload can never succeed — surface it as a terminal
                // failure so the queue flags the op past its retry ceiling instead of the decode
                // throwing out of the drain loop and aborting every op in the wave forever.
                return AppResult.Failure(
                    SyncError.SyncFailed(debugInfo = "failed to decode '${domain.name}' payload: ${e.message}"),
                )
            }
        return when (val result = push(op.entityId, patch)) {
            is WireAppResult.Success -> AppResult.Success(Unit)
            is WireAppResult.Failure -> AppResult.Failure(result.error)
        }
    }
}
