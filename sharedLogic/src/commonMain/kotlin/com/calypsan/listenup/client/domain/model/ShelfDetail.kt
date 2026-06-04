package com.calypsan.listenup.client.domain.model

/**
 * Domain model representing full shelf details for the shelf detail screen.
 *
 * Contains all information needed to display a shelf's detail view,
 * including ownership/privacy flags, stats, and the list of books.
 *
 * For non-owners, [books] is already access-filtered by the server — books the
 * viewer cannot access are silently excluded, so [bookCount] reflects only the
 * visible set.
 *
 * @property id Unique shelf identifier
 * @property name Shelf display name
 * @property description Optional shelf description
 * @property isPrivate `true` if only the owner can see this shelf
 * @property isOwner `true` when the current user owns this shelf (gates edit/delete/reorder)
 * @property bookCount Number of books visible to the viewer
 * @property totalDurationSeconds Total duration of all visible books in seconds
 * @property books Ordered list of books in the shelf
 */
data class ShelfDetail(
    val id: String,
    val name: String,
    val description: String?,
    val isPrivate: Boolean,
    val isOwner: Boolean,
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
}

/**
 * A book within a shelf.
 *
 * The server's shelf book view supplies only display-critical fields; per-book
 * duration is not part of the contract, so it is omitted here. [coverPath] is
 * resolved client-side from the local image cache when available.
 *
 * @property id Book's unique identifier
 * @property title Book title
 * @property authorNames List of author names for this book
 * @property coverPath Local path to cover image (optional)
 */
data class ShelfBook(
    val id: String,
    val title: String,
    val authorNames: List<String>,
    val coverPath: String?,
)
