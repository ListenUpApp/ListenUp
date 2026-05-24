package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for one user's currently-active listening session. Written by the client
 * when playback starts and soft-deleted when playback stops or the book is finished.
 *
 * The payload is used by other clients to populate the "What Others Are Listening To"
 * feature on the Discover screen. A row exists only while the user is actively
 * listening; the completion cascade in `PlaybackPositionRepository.recordPosition`
 * hard-deletes the server-side row when `finished` flips `false→true`.
 *
 * `sessionId` is a client-assigned UUID that survives a reconnect — the client
 * re-upserts the same `sessionId` when it rejoins, so no phantom rows accumulate.
 *
 * `startedAt` is the wall-clock ms of when the session began; `updatedAt` is
 * refreshed on every upsert so the cleanup sweep can evict orphan rows after a
 * configurable staleness threshold (default 30 minutes).
 *
 * The wire payload deliberately does not carry the user's display name or the
 * book's title — the server joins those at the `GET /api/v1/sync/active-sessions`
 * REST endpoint so the sync substrate ships only the authoritative primary keys.
 *
 * Implements [Tombstoned] for uniform soft-delete routing — the `finished`-flip
 * cascade uses a hard-delete, but the substrate's soft-delete path is retained
 * for the client-initiated `session.ended` event.
 */
@Serializable
@SerialName("ActiveSessionSyncPayload")
data class ActiveSessionSyncPayload(
    /** Client-assigned UUID stable across reconnects for this listening session. */
    val sessionId: String,
    /** Book the user is currently listening to. */
    val bookId: String,
    /** Wall-clock ms when this session started. */
    val startedAt: Long,
    val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
) : Tombstoned
