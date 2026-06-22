package com.calypsan.listenup.client.domain.model

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
    fun formatDuration(): String {
        val totalSeconds = duration / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}
