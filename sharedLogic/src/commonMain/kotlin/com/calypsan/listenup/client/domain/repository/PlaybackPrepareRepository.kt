@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.result.AppResult
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
}
