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
    /** Non-null on the chapter that opens a Part; the free-text Part title. */
    val partTitle: String? = null,
    /** Non-null on the chapter that opens a Book; the free-text Book title. May co-occur with [partTitle]. */
    val bookTitle: String? = null,
) {
    fun formatDuration(): String = DurationFormatter.minutesSecondsClock(duration.milliseconds)
}
