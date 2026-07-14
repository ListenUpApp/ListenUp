package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.core.BookId

/**
 * Routes [prepare] through the bounded, single-flight, self-healing [RpcChannel] — the one dispatch
 * path every [com.calypsan.listenup.api.PlaybackService.prepare] caller shares. There is no
 * raw-proxy access: the channel folds a transport fault into a typed [AppResult.Failure] and passes
 * a business failure through untouched.
 */
internal class PlaybackPrepareRepositoryImpl(
    private val channel: RpcChannel<PlaybackService>,
) : PlaybackPrepareRepository {
    override suspend fun prepare(bookId: BookId): AppResult<PreparedPlayback> = channel.call { it.prepare(bookId) }

    // idempotent read — safe for the channel to retry / single-flight.
    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> =
        channel.call(idempotent = true) { it.getPosition(bookId) }
}
