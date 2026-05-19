package com.calypsan.listenup.client.domain.model

import kotlin.math.roundToInt

/**
 * Domain model for a book in the "Continue Listening" section.
 *
 * Combines playback progress data with book metadata for UI display.
 * Provides computed properties for progress display and time remaining.
 */
data class ContinueListeningBook(
    val bookId: String,
    val title: String,
    val authorNames: String,
    val coverPath: String?,
    val coverBlurHash: String? = null,
    // 0.0 - 1.0
    val progress: Float,
    val currentPositionMs: Long,
    val totalDurationMs: Long,
    // ISO 8601 timestamp
    val lastPlayedAt: String,
) {
    /**
     * Time remaining in milliseconds.
     */
    val timeRemainingMs: Long
        get() = (totalDurationMs - currentPositionMs).coerceAtLeast(0)

    /**
     * Progress as percentage (0-100).
     */
    val progressPercent: Int
        get() = (progress * 100).roundToInt()

    /**
     * Human-readable time remaining.
     * Examples: "Almost done", "45 min left", "2h 15m left"
     */
    val timeRemainingFormatted: String
        get() = formatDuration(timeRemainingMs)

    companion object {
        /**
         * Format milliseconds into human-readable duration.
         */
        fun formatDuration(ms: Long): String {
            val totalMinutes = ms / 60_000
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60

            @Suppress("MagicNumber")
            return when {
                ms < 5 * 60_000 -> "Almost done"
                ms < 60 * 60_000 -> "$totalMinutes min left"
                hours < 2 -> "1 hr $minutes min left"
                else -> "${hours}h ${minutes}m left"
            }
        }
    }
}
