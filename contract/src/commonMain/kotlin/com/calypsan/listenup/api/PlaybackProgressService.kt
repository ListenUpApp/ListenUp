package com.calypsan.listenup.api

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.core.BookId
import kotlinx.rpc.annotations.Rpc

/**
 * Server-side query surface for per-user listening progress. Methods are
 * implicitly user-scoped — each returns ONLY the authenticated user's positions
 * via [com.calypsan.listenup.server.principal.PrincipalProvider]. No admin
 * gating is needed.
 *
 * Sits above the existing playback_positions sync substrate (P1) — clients with
 * full sync already have these positions in Room; this RPC is for REST/RPC
 * consumers, third-party integrations, and future iOS/Desktop without substrate.
 */
@Rpc
interface PlaybackProgressService {
    /**
     * All positions for the current authenticated user (excluding tombstones).
     * Order is unspecified — callers sort if needed.
     *
     * @param limit max positions to return. Default 100; clamped to [1, 500] server-side.
     */
    suspend fun listProgress(limit: Int = 100): AppResult<List<PlaybackPositionSyncPayload>>

    /**
     * Sparse batch lookup — returns only positions that exist for the given
     * bookIds. Missing positions (no progress recorded) are silently omitted; the
     * caller compares the requested set against returned bookIds.
     *
     * @param bookIds the set of book identifiers to look up progress for.
     */
    suspend fun getProgressBatch(bookIds: List<BookId>): AppResult<List<PlaybackPositionSyncPayload>>

    /**
     * Continue-listening semantics — books the user has started but not finished,
     * ordered by `lastPlayedAt DESC` (with `updatedAt` fallback when null).
     * Matches the client Continue Listening shelf's filter.
     *
     * @param limit max positions to return. Default 20; clamped to [1, 100] server-side.
     */
    suspend fun getRecentlyListened(limit: Int = 20): AppResult<List<PlaybackPositionSyncPayload>>

    /**
     * Books the user finished, ordered by `lastPlayedAt DESC`. Filters
     * `isFinished = true` AND not tombstoned.
     *
     * @param limit max positions to return. Default 50; clamped to [1, 500] server-side.
     */
    suspend fun getCompletedBooks(limit: Int = 50): AppResult<List<PlaybackPositionSyncPayload>>
}
