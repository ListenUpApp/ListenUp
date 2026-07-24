package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.client.data.sync.domains.OutboxChannel
import kotlinx.coroutines.CancellationException

/**
 * The one generic outbox sender: decodes the queued payload with its channel's
 * serializer and pushes it through [push] (one RPC call). Every outbox channel
 * binds an instance via [outboxBinding]; hand-rolled per-domain senders are banned
 * by Konsist (`OnlyOutboxOpSenderImplementsSenderRule`).
 *
 * A corrupt/schema-drifted payload is surfaced as [SyncError.SyncFailed] rather
 * than thrown so the drain wave can quarantine the op. Note: `SyncFailed` is
 * retryable by contract, so a permanently-corrupt payload burns its retry budget
 * (5 attempts) before dead-lettering — bounded waste, visible in the dead-letter
 * surface; accepted over minting a new contract error type.
 */
internal class OutboxOpSender<T : Any>(
    private val channel: OutboxChannel<T>,
    private val push: suspend (entityId: String, payload: T) -> WireAppResult<*>,
) : PendingOperationSender {
    override suspend fun send(op: PendingOperation): AppResult<Unit> {
        val payload =
            try {
                contractJson.decodeFromString(channel.serializer, op.payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return AppResult.Failure(
                    SyncError.SyncFailed(debugInfo = "failed to decode '${channel.name}' payload: ${e.message}"),
                )
            }
        return when (val result = push(op.entityId, payload)) {
            is WireAppResult.Success -> AppResult.Success(Unit)
            is WireAppResult.Failure -> AppResult.Failure(result.error)
        }
    }
}
