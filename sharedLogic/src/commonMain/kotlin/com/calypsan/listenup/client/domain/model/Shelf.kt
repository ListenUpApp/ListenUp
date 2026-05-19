package com.calypsan.listenup.client.domain.model

/**
 * Domain model for a personal curation shelf.
 *
 * Shelves are user-created curated lists of books for personal organization
 * and social discovery. Each user can create multiple shelves to organize
 * their reading journey.
 *
 * @property id Unique identifier
 * @property name Display name (e.g., "To Read", "Favorites")
 * @property description Optional description
 * @property ownerId User who created this shelf
 * @property ownerDisplayName Owner's display name for social context
 * @property ownerAvatarColor Owner's avatar color for UI display
 * @property bookCount Number of books in this shelf
 * @property totalDurationSeconds Total duration of all books in seconds
 * @property createdAtMs Creation timestamp
 * @property updatedAtMs Last update timestamp
 */
data class Shelf(
    val id: String,
    val name: String,
    val description: String?,
    val ownerId: String,
    val ownerDisplayName: String,
    val ownerAvatarColor: String,
    val bookCount: Int,
    val totalDurationSeconds: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val coverPaths: List<String> = emptyList(),
) {
    /**
     * Returns the display name formatted for the current user context.
     * - Owner sees: "To Read"
     * - Others see: "Simon's To Read"
     */
    fun displayName(currentUserId: String): String = if (ownerId == currentUserId) name else "$ownerDisplayName's $name"

    /**
     * Returns true if this shelf belongs to the given user.
     */
    fun isOwnedBy(userId: String): Boolean = ownerId == userId

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
