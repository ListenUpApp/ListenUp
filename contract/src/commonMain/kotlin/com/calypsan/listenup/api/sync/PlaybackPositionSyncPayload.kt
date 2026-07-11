package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a user's playback position in one book — the cross-device
 * resume point. A per-user syncable domain (Playback P1).
 *
 * `lastPlayedAt` is the wall-clock of the actual listening moment this position
 * represents; it is the **conflict key** — the server keeps the write with the
 * greatest `lastPlayedAt`, so a stale offline write never clobbers a fresher
 * position from another device.
 *
 * Implements [Tombstoned] for uniform soft-delete routing (a position is
 * tombstoned only when its book is deleted).
 *
 * The wire payload deliberately does not carry `userId` — the client only ever
 * receives its own rows; userId is a server-side routing/storage concern.
 */
@Serializable
@SerialName("PlaybackPositionSyncPayload")
data class PlaybackPositionSyncPayload(
    override val id: String,
    val bookId: String,
    val positionMs: Long,
    val lastPlayedAt: Long,
    val finished: Boolean,
    val playbackSpeed: Float,
    val currentChapterId: String?,
    override val revision: Long,
    val updatedAt: Long,
    val createdAt: Long,
    override val deletedAt: Long?,
    // The furthest position ever heard in this book — the high-water mark for the
    // spoiler-safe frontier (Story World Stage 3). Merged by `max` everywhere it is
    // written or synced (client hot path, client mirror-apply, server merge); it
    // never decreases, not even on DiscardProgress/Restart, because the user still
    // *heard* that far — only the resume point resets. Default 0 keeps old
    // servers/clients wire-compatible: `max(0, x) = x`.
    @SerialName("maxPositionMs") val maxPositionMs: Long = 0,
) : SyncPayload
