package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.PlaybackRpcFactory
import kotlinx.coroutines.CancellationException

/**
 * Pushes a queued listening-event write to the server via
 * [com.calypsan.listenup.api.PlaybackService.recordListeningEvent].
 *
 * Idempotent server-side (re-recording the same `id` is a no-op on the substrate's
 * `upsert`), so the pending-operation queue may safely re-fire on retry without
 * risk of creating duplicate events.
 *
 * The [PendingOperation.payload] is a JSON-encoded [RecordListeningEventRequest].
 * The sender decodes it at dispatch time (not enqueue time) so the queue row is the
 * single durable representation of the pending write. A corrupt/schema-drifted
 * payload is surfaced as a [SyncError.SyncFailed] failure rather than thrown, so
 * the drain wave can quarantine the op instead of aborting on the decode.
 */
internal class ListeningEventOpSender(
    private val rpcFactory: PlaybackRpcFactory,
) : PendingOperationSender {
    override suspend fun send(op: PendingOperation): AppResult<Unit> {
        val request =
            try {
                contractJson.decodeFromString(RecordListeningEventRequest.serializer(), op.payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return AppResult.Failure(
                    SyncError.SyncFailed(debugInfo = "failed to decode 'listening_events' payload: ${e.message}"),
                )
            }
        return when (val result = rpcFactory.playbackService().recordListeningEvent(request)) {
            is WireAppResult.Success -> AppResult.Success(Unit)
            is WireAppResult.Failure -> AppResult.Failure(result.error)
        }
    }
}
