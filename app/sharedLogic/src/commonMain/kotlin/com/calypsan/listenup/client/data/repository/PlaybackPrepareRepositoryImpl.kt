package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.core.BookId
import kotlin.time.Duration.Companion.milliseconds

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

    /**
     * Bounded SHORT and never retried, because this call sits between a listener tapping play and
     * hearing anything.
     *
     * It ran on the 15s channel default with `idempotent = true`, which licensed
     * [com.calypsan.listenup.client.data.remote.RpcProxyCache] to re-fire on timeout — so a
     * half-open socket cost 15s + 15s and a fully-downloaded book took 30 seconds to start.
     *
     * A healthy socket answers in ~20ms, so the resume point is still server-correct in the case
     * that matters. A degraded one gives up here and resume falls back to the local Room row, which
     * is survivable in a way waiting is not: starting playback no longer claims position authority,
     * so a stale local row can no longer discard another device's progress.
     */
    override suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?> =
        channel.call(timeout = RESUME_POSITION_FETCH_BOUND, idempotent = false) { it.getPosition(bookId) }

    private companion object {
        /** Latency budget for the resume reconcile — see [getPosition]. */
        private val RESUME_POSITION_FETCH_BOUND = 800.milliseconds
    }
}
