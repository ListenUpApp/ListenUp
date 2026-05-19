package com.calypsan.listenup.client.data.remote.model

import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wrapper response for GET /api/v1/listening/continue endpoint.
 */
@Serializable
data class ContinueListeningResponse(
    @SerialName("items")
    val items: List<ContinueListeningItemResponse>,
)

/**
 * Individual item from GET /api/v1/listening/continue endpoint.
 *
 * Display-ready item that combines progress with book details,
 * eliminating the need for client-side joins.
 */
@Serializable
data class ContinueListeningItemResponse(
    @SerialName("book_id")
    val bookId: String,
    @SerialName("current_position_ms")
    val currentPositionMs: Long,
    @SerialName("progress")
    val progress: Double, // 0.0 - 1.0
    @SerialName("last_played_at")
    val lastPlayedAt: String, // ISO 8601 timestamp
    @SerialName("title")
    val title: String,
    @SerialName("author_name")
    val authorName: String,
    @SerialName("cover_path")
    val coverPath: String? = null,
    @SerialName("cover_blur_hash")
    val coverBlurHash: String? = null,
    @SerialName("total_duration_ms")
    val totalDurationMs: Long,
) {
    /**
     * Convert to domain model for UI display.
     */
    fun toDomain(): ContinueListeningBook =
        ContinueListeningBook(
            bookId = bookId,
            title = title,
            authorNames = authorName,
            coverPath = coverPath,
            coverBlurHash = coverBlurHash,
            progress = progress.toFloat(),
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            lastPlayedAt = lastPlayedAt,
        )
}
