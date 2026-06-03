package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.result.AppResult as WireAppResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.PlaybackRpcFactory

/**
 * Pushes a queued playback-position write to the server via
 * [com.calypsan.listenup.api.PlaybackService.recordPosition].
 *
 * Idempotent and `lastPlayedAt`-wins server-side, so the pending-operation queue
 * may safely re-fire on retry without risk of regressing the server's stored
 * position.
 *
 * The [PendingOperation.payload] is a JSON-encoded [RecordPositionRequest]. The
 * sender decodes it at dispatch time (not enqueue time) so the queue row is the
 * single durable representation of the pending write.
 */
class PlaybackPositionOpSender(
    private val rpcFactory: PlaybackRpcFactory,
) : PendingOperationSender {
    override suspend fun send(op: PendingOperation): AppResult<Unit> {
        val request =
            contractJson.decodeFromString(RecordPositionRequest.serializer(), op.payload)
        return when (val result = rpcFactory.playbackService().recordPosition(request)) {
            is WireAppResult.Success -> AppResult.Success(Unit)
            is WireAppResult.Failure -> AppResult.Failure(result.error)
        }
    }
}
