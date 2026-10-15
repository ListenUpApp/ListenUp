package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.client.core.DurationFormatter
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Domain model for a book in the "Continue Listening" section.
 *
 * Combines playback progress data with book metadata for UI display.
 * Provides computed properties for progress display and time remaining.
 *
 * @property narratorNames the narrators joined with ", ", or null when the book names none.
 * @property lastPlayedAtMs when the reader last played it, epoch ms (the position's lastPlayedAt, else its updatedAt).
 * @property isDownloaded every file is on this device, so it plays with no server.
 */
data class ContinueListeningBook(
    val bookId: String,
    val title: String,
    val authorNames: String,
    val narratorNames: String?,
    val coverPath: String?,
    val coverHash: String? = null,
    // 0.0 - 1.0
    val progress: Float,
    val currentPositionMs: Long,
    val totalDurationMs: Long,
    val lastPlayedAtMs: Long,
    val isDownloaded: Boolean,
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
        fun formatDuration(ms: Long): String = DurationFormatter.timeLeft(ms.milliseconds)
    }
}
