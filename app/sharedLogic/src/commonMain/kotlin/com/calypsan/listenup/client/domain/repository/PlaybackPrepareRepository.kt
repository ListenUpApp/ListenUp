@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.core.BookId

/**
 * The single seam for [com.calypsan.listenup.api.PlaybackService.prepare] — signed audio URLs plus
 * the caller's resume position for a book, in one round-trip.
 *
 * Public (unlike the internal `RpcChannel`) so every cross-module `prepare` caller reaches the RPC
 * through one door: the Android Cast handoff in `:app:sharedUI`, the shared download-URL resolver, and
 * the playback-timeline builder. The production implementation folds the call through the bounded,
 * single-flight, self-healing RPC channel; a business [AppResult.Failure] returned by the service
 * passes through untouched.
 */
interface PlaybackPrepareRepository {
    /** Signed stream URLs for [bookId] plus the caller's resume position — one round-trip. */
    suspend fun prepare(bookId: BookId): AppResult<PreparedPlayback>

    /**
     * The server-authoritative resume position for [bookId], or null if the server has none.
     *
     * The fully-downloaded playback path skips [prepare] entirely (offline-first), so it never sees
     * the server's position and cannot reconcile a stale local Room row against another device's
     * newer progress. This best-effort read closes that clobber for downloaded books — the caller
     * folds the result through the same newer-wins merge and degrades to the local row on any
     * failure.
     *
     * **This DOES block — it is awaited, on the path between tapping play and hearing audio.** It is
     * bounded SHORT (the production implementation's timeout override), so the cost is sub-second
     * even when the socket is dead, and any failure (including a timeout) returns
     * [AppResult.Failure] rather than hanging — but it is not "non-blocking," and treating it as such
     * is what let an earlier, unbounded version of this call stall the tap-to-audio path for tens of
     * seconds. See [com.calypsan.listenup.client.playback.PlaybackPreparer.fetchAuthoritativePosition]
     * for the caller-side accounting of that cost.
     */
    suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?>
}
