package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.PlaybackRpcFactory

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
 * single durable representation of the pending write.
 */
class ListeningEventOpSender(
    private val rpcFactory: PlaybackRpcFactory,
) : PendingOperationSender {
    override suspend fun send(op: PendingOperation): AppResult<Unit> {
        val request =
            contractJson.decodeFromString(RecordListeningEventRequest.serializer(), op.payload)
        return when (val result = rpcFactory.playbackService().recordListeningEvent(request)) {
            is WireAppResult.Success -> AppResult.Success(Unit)
            is WireAppResult.Failure -> AppResult.Failure(result.error)
        }
    }
}
