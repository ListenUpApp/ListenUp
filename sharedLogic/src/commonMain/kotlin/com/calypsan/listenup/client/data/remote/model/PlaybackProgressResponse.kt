@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.remote.model

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Response from GET /api/v1/listening/continue endpoint.
 *
 * Represents a user's playback progress on an audiobook.
 * Used to build the "Continue Listening" section on the Home screen.
 */
@Serializable
data class PlaybackProgressResponse(
    @SerialName("user_id")
    val userId: String,
    @SerialName("book_id")
    val bookId: String,
    @SerialName("current_position_ms")
    val currentPositionMs: Long,
    @SerialName("progress")
    val progress: Double, // 0.0 - 1.0
    @SerialName("is_finished")
    val isFinished: Boolean,
    @SerialName("started_at")
    val startedAt: String, // ISO 8601 timestamp
    @SerialName("last_played_at")
    val lastPlayedAt: String, // ISO 8601 timestamp
    @SerialName("total_listen_time_ms")
    val totalListenTimeMs: Long,
    // ISO 8601 timestamp
    @SerialName("updated_at")
    val updatedAt: String,
) {
    /**
     * Convert to local PlaybackPositionEntity for caching.
     *
     * Uses the server's own timestamps (not Clock.System.now()) so that
     * future local-vs-server comparisons use consistent time bases.
     * Using local clock would inflate updatedAt, causing stale local positions
     * to win over newer server positions on subsequent app launches.
     */
    internal fun toEntity(): PlaybackPositionEntity {
        val serverUpdatedAt = updatedAtMillis()
        return PlaybackPositionEntity(
            bookId = BookId(bookId),
            positionMs = currentPositionMs,
            playbackSpeed = 1.0f, // Server doesn't track per-position speed
            updatedAt = serverUpdatedAt,
            syncedAt = serverUpdatedAt,
            lastPlayedAt = lastPlayedAtMillis(),
            isFinished = isFinished, // Trust server's authoritative finished status
        )
    }

    /**
     * Parse lastPlayedAt ISO 8601 timestamp to epoch milliseconds.
     */
    fun lastPlayedAtMillis(): Long =
        try {
            Instant.parse(lastPlayedAt).toEpochMilliseconds()
        } catch (_: Exception) {
            0L
        }

    /**
     * Parse updatedAt ISO 8601 timestamp to epoch milliseconds.
     */
    fun updatedAtMillis(): Long =
        try {
            Instant.parse(updatedAt).toEpochMilliseconds()
        } catch (_: Exception) {
            0L
        }
}
