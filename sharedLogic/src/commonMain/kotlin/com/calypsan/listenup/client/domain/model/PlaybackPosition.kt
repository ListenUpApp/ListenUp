package com.calypsan.listenup.client.domain.model

/**
 * Domain model representing a playback position for a book.
 *
 * Tracks where the user left off listening, enabling seamless resume
 * across sessions and devices. Position is the most sacred data in the app.
 *
 * @property bookId The book this position is for
 * @property positionMs Current position in the book (milliseconds)
 * @property playbackSpeed Last used playback speed for this book
 * @property hasCustomSpeed Whether user explicitly set a custom speed
 * @property updatedAtMs When position was last modified locally
 * @property syncedAtMs When position was last synced to server
 * @property lastPlayedAtMs When user actually last played this book
 * @property isFinished Whether the book is marked as finished (authoritative from server)
 * @property finishedAtMs When the book was marked finished (epoch ms)
 * @property startedAtMs When the user started this book (epoch ms)
 */
data class PlaybackPosition(
    val bookId: String,
    val positionMs: Long,
    val playbackSpeed: Float,
    val hasCustomSpeed: Boolean,
    val updatedAtMs: Long,
    val syncedAtMs: Long?,
    val lastPlayedAtMs: Long?,
    val isFinished: Boolean = false,
    val finishedAtMs: Long? = null,
    val startedAtMs: Long? = null,
) {
    /**
     * Returns true if this position has pending sync changes.
     */
    val needsSync: Boolean
        get() = syncedAtMs == null || syncedAtMs < updatedAtMs

    /**
     * Returns the effective last played timestamp, falling back to updatedAt for legacy data.
     */
    val effectiveLastPlayedAtMs: Long
        get() = lastPlayedAtMs ?: updatedAtMs

    /**
     * Returns the position as a human-readable string (e.g., "1:23:45").
     */
    val formattedPosition: String
        get() {
            val totalSeconds = positionMs / 1000
            val hours = totalSeconds / 3600
            val minutes = totalSeconds % 3600 / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
            } else {
                "$minutes:${seconds.toString().padStart(2, '0')}"
            }
        }
}
