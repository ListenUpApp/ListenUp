package com.calypsan.listenup.api.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTO for a user's follow-state on one series: which reading order (if any)
 * is the active "spoiler clock" for that series (Integration Foundations §5.4).
 *
 * One row per `(user, series)`. [activeReadingOrderId] is nullable — null means
 * the per-book frontier, the graceful floor. Strictly personal: the server stores
 * and syncs the setting but never interprets it.
 *
 * [id] is the deterministic synthetic key `"$userId:$seriesId"`, computable on
 * both sides before any echo — so the optimistic client write, the server row,
 * and the digest all agree on identity (unlike the playback-position UUID, whose
 * client/server key mismatch forces a digest opt-out).
 *
 * `userId` is NOT otherwise on the wire — the sync substrate scopes pull/firehose
 * queries to the authenticated user's rows.
 */
@Serializable
@SerialName("ReadingOrderFollowSyncPayload")
data class ReadingOrderFollowSyncPayload(
    /** Deterministic synthetic id (`"$userId:$seriesId"`) — the sync-cursor identity. */
    @SerialName("id") override val id: String,
    /** The series this follow-state applies to. */
    @SerialName("seriesId") val seriesId: String,
    /** The active reading order for [seriesId], or null for the per-book frontier floor. */
    @SerialName("activeReadingOrderId") val activeReadingOrderId: String? = null,
    /** Sync revision counter — bumped on every write. */
    @SerialName("revision") override val revision: Long,
    /** Epoch millis of the last server-side write. */
    @SerialName("updatedAt") val updatedAt: Long,
    /** Epoch millis when this follow row was first created. */
    @SerialName("createdAt") val createdAt: Long,
    @SerialName("deletedAt") override val deletedAt: Long? = null,
) : SyncPayload
