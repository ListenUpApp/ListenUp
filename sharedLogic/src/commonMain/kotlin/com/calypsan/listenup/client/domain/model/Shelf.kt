package com.calypsan.listenup.client.domain.model

/**
 * Domain model for a personal curation shelf.
 *
 * Shelves are user-created curated lists of books for personal organization
 * and social discovery. Each user can create multiple shelves to organize
 * their reading journey.
 *
 * Owner identity (`ownerId`, `ownerDisplayName`) is populated from the current
 * user for the caller's own shelves and from the server for discovered shelves.
 * `coverPaths` and `totalDurationSeconds` are derived from the shelf's member
 * books in the local Room mirror — discovered (other-user) shelves carry empty
 * covers and zero duration because their member books never enter the mirror.
 *
 * @property id Unique identifier
 * @property name Display name (e.g., "To Read", "Favorites")
 * @property description Optional description
 * @property isPrivate `true` if only the owner can see this shelf
 * @property ownerId User who created this shelf
 * @property ownerDisplayName Owner's display name for social context
 * @property bookCount Number of books in this shelf
 * @property totalDurationSeconds Total duration of all books in seconds
 * @property createdAtMs Creation timestamp
 * @property updatedAtMs Last update timestamp
 * @property coverPaths Cover hashes for the shelf's first few member books, in sort order
 */
data class Shelf(
    val id: String,
    val name: String,
    val description: String?,
    val isPrivate: Boolean,
    val ownerId: String,
    val ownerDisplayName: String,
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
