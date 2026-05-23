package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.PreparedPlayback
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.PlaybackPositionSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.core.BookId
import kotlinx.rpc.annotations.Rpc

/**
 * The single RPC surface for client playback. [prepare] returns signed audio
 * URLs and the caller's resume position in one round-trip (the "no more
 * sequential REST calls before play" goal of Playback P1). [recordPosition] is
 * the client→server write path — idempotent and `lastPlayedAt`-wins server-side
 * so the pending-operation queue may safely re-fire on retry.
 *
 * [getStats] and [recordListeningEvent] extend the surface in Playback P2 with
 * the listening-event append-only log and its materialized stats projection.
 */
@Rpc
interface PlaybackService {
    /**
     * Signed stream URLs for [bookId] plus the caller's resume position — one
     * call, everything the player needs.
     */
    suspend fun prepare(bookId: BookId): AppResult<PreparedPlayback>

    /**
     * The caller's playback position for [bookId], or null — the never-stranded
     * cache-miss read.
     */
    suspend fun getPosition(bookId: BookId): AppResult<PlaybackPositionSyncPayload?>

    /**
     * Record the caller's playback position. Idempotent and `lastPlayedAt`-wins
     * server-side — safe for the client's pending-operation queue to re-fire.
     * Returns the authoritative stored position after the write.
     */
    suspend fun recordPosition(request: RecordPositionRequest): AppResult<PlaybackPositionSyncPayload>

    /**
     * The caller's materialized listening stats, or null if they have no listening
     * history yet — the never-stranded cache-miss read.
     */
    suspend fun getStats(): AppResult<UserStatsSyncPayload?>

    /**
     * Record one closed listening span. Idempotent (re-recording the same `id`
     * is a no-op on the substrate's `upsert`). Returns the authoritative stored
     * event after the server-side write + stats update.
     */
    suspend fun recordListeningEvent(request: RecordListeningEventRequest): AppResult<ListeningEventSyncPayload>
}
