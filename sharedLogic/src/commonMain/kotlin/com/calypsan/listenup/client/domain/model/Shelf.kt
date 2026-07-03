package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.core.ShelfId
import kotlin.time.Duration.Companion.seconds

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
 * @property id Unique identifier (typed [ShelfId])
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
    val id: ShelfId,
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
    /** The shelf id as a plain String, for the Swift/SKIE boundary (the value class is unboxed there). */
    val idString: String get() = id.value

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
        get() = DurationFormatter.hoursMinutesCompact(totalDurationSeconds.seconds)
}
