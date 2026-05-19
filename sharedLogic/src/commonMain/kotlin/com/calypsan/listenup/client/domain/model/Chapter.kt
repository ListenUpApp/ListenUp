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
) {
    fun formatDuration(): String {
        val totalSeconds = duration / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}
