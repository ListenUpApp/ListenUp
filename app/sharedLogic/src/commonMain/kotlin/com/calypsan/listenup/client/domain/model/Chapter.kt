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
    /**
     * The name of the Part this chapter OPENS, or null when it opens none.
     *
     * A header, not a parent pointer: grouping is derived by reading the ordered list and starting
     * a new group wherever a title appears. Nothing needs renumbering when a chapter moves.
     */
    val partTitle: String? = null,
    /** The name of the Book this chapter OPENS, or null. May co-occur with [partTitle]. */
    val bookTitle: String? = null,
) {
    fun formatDuration(): String = DurationFormatter.minutesSecondsClock(duration.milliseconds)
}
