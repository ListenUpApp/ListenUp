package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.domain.BookProgress
import kotlin.time.Duration.Companion.milliseconds

/**
 * Domain model for a book in the "Continue Listening" section.
 *
 * Combines playback progress data with book metadata for UI display. Its progress, percent and time left are
 * [BookProgress]'s, read from [currentPositionMs] and [totalDurationMs]: never a second rule.
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
    val currentPositionMs: Long,
    val totalDurationMs: Long,
    val lastPlayedAtMs: Long,
    val isDownloaded: Boolean,
) {
    /**
     * Where the reader is, by the one rule. Continue Listening holds only unfinished books, so this is
     * [BookProgress.InProgress] except for a book whose length is not known yet, which has no percent to show.
     */
    private val inProgress: BookProgress.InProgress?
        get() = BookProgress.of(currentPositionMs, totalDurationMs, finishedFlag = false) as? BookProgress.InProgress

    /** How far through, `0..1`, for the bar; 0 for a book of unknown length. */
    val progress: Float
        get() = inProgress?.fraction ?: 0f

    /** The percent to print ([BookProgress.InProgress.percent]); 0 for a book of unknown length. */
    val progressPercent: Int
        get() = inProgress?.percent ?: 0

    /** Time remaining in milliseconds ([BookProgress.InProgress.remainingMs]); 0 for a book of unknown length. */
    val timeRemainingMs: Long
        get() = inProgress?.remainingMs ?: 0L

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
