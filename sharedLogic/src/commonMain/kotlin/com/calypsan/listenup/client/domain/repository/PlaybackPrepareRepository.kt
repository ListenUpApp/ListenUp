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
 * through one door: the Android Cast handoff in `:sharedUI`, the shared download-URL resolver, and
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
     * failure (offline), never blocking or failing playback.
     */
    suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?>
}
