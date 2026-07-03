package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.client.core.DurationFormatter
import kotlin.time.Duration.Companion.milliseconds

/**
 * Domain model for a book chapter.
 */
data class Chapter(
    val id: String,
    val title: String,
    // Milliseconds
    val duration: Long,
    // Milliseconds
    val startTime: Long,
) {
    fun formatDuration(): String = DurationFormatter.minutesSecondsClock(duration.milliseconds)
}
