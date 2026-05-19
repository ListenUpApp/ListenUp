package com.calypsan.listenup.client.domain.model

/**
 * Domain model representing full shelf details for the shelf detail screen.
 *
 * Contains all information needed to display a shelf's detail view,
 * including owner info, stats, and the list of books.
 *
 * @property id Unique shelf identifier
 * @property name Shelf display name
 * @property description Optional shelf description
 * @property owner Information about the shelf owner
 * @property bookCount Number of books in the shelf
 * @property totalDurationSeconds Total duration of all books in seconds
 * @property books List of books in the shelf
 */
data class ShelfDetail(
    val id: String,
    val name: String,
    val description: String?,
    val owner: ShelfOwner,
    val bookCount: Int,
    val totalDurationSeconds: Long,
    val books: List<ShelfBook>,
) {
    /**
     * Returns the total duration formatted as hours and minutes.
     */
    val formattedDuration: String
        get() {
            val hours = totalDurationSeconds / 3600
            val minutes = totalDurationSeconds % 3600 / 60
            return when {
                hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
                hours > 0 -> "${hours}h"
                minutes > 0 -> "${minutes}m"
                else -> "0m"
            }
        }

    /**
     * Returns true if the given user owns this shelf.
     */
    fun isOwnedBy(userId: String): Boolean = owner.id == userId
}

/**
 * Owner information for a shelf.
 *
 * @property id Owner's user ID
 * @property displayName Owner's display name
 * @property avatarColor Owner's avatar color (hex format)
 */
data class ShelfOwner(
    val id: String,
    val displayName: String,
    val avatarColor: String,
)

/**
 * A book within a shelf.
 *
 * @property id Book's unique identifier
 * @property title Book title
 * @property authorNames List of author names for this book
 * @property coverPath Local path to cover image (optional)
 * @property durationSeconds Book duration in seconds
 */
data class ShelfBook(
    val id: String,
    val title: String,
    val authorNames: List<String>,
    val coverPath: String?,
    val durationSeconds: Long,
) {
    /**
     * Returns formatted duration (e.g., "8h 30m").
     */
    val formattedDuration: String
        get() {
            val hours = durationSeconds / 3600
            val minutes = durationSeconds % 3600 / 60
            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m"
                else -> "0m"
            }
        }
}
