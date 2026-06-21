@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.remote.model

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Response from GET /api/v1/listening/progress endpoint.
 *
 * Returns all playback progress for the current user.
 * Used for bulk sync to ensure client has accurate isFinished state.
 */
@Serializable
data class AllProgressResponse(
    @SerialName("items")
    val items: List<ProgressSyncItem>,
)

/**
 * A single progress record for sync.
 */
@Serializable
data class ProgressSyncItem(
    @SerialName("book_id")
    val bookId: String,
    @SerialName("current_position_ms")
    val currentPositionMs: Long,
    @SerialName("is_finished")
    val isFinished: Boolean,
    @SerialName("finished_at")
    val finishedAt: String? = null, // ISO 8601 timestamp, nullable
    @SerialName("started_at")
    val startedAt: String? = null, // ISO 8601 timestamp, when user started this book
    @SerialName("last_played_at")
    val lastPlayedAt: String, // ISO 8601 timestamp
    @SerialName("updated_at")
    val updatedAt: String, // ISO 8601 timestamp
) {
    /**
     * Convert to local PlaybackPositionEntity for upsert.
     *
     * @param existing Optional existing entity to preserve local-only fields
     */
    internal fun toEntity(existing: PlaybackPositionEntity? = null): PlaybackPositionEntity =
        PlaybackPositionEntity(
            bookId = BookId(bookId),
            positionMs = currentPositionMs,
            playbackSpeed = existing?.playbackSpeed ?: 1.0f,
            hasCustomSpeed = existing?.hasCustomSpeed ?: false,
            updatedAt = parseTimestamp(updatedAt),
            syncedAt = parseTimestamp(updatedAt),
            lastPlayedAt = parseTimestamp(lastPlayedAt),
            isFinished = isFinished,
            finishedAt = finishedAt?.let { parseTimestamp(it) },
            startedAt = startedAt?.let { parseTimestamp(it) } ?: existing?.startedAt,
        )

    /**
     * Parse ISO 8601 timestamp to epoch milliseconds.
     */
    private fun parseTimestamp(isoTimestamp: String): Long =
        try {
            Instant.parse(isoTimestamp).toEpochMilliseconds()
        } catch (_: Exception) {
            0L
        }
}
