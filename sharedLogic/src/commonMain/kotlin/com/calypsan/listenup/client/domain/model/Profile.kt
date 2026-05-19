package com.calypsan.listenup.client.domain.model

/**
 * Domain model representing a user's public profile.
 *
 * Contains all information displayed on a user's profile page,
 * including their stats, recent activity, and public shelves.
 *
 * @property userId User's unique identifier
 * @property displayName User's display name
 * @property avatarType Type of avatar: "auto" for generated, "image" for uploaded
 * @property avatarValue Path/URL to avatar image when type is "image"
 * @property avatarColor Background color for generated avatars (hex format)
 * @property tagline User's profile tagline/bio (optional)
 * @property totalListenTimeMs Total listening time in milliseconds
 * @property booksFinished Number of books completed
 * @property currentStreak Current daily listening streak
 * @property longestStreak Longest daily listening streak achieved
 * @property recentBooks List of recently listened books
 * @property publicShelves List of user's public shelves
 */
data class UserProfile(
    val userId: String,
    val displayName: String,
    val avatarType: String,
    val avatarValue: String?,
    val avatarColor: String,
    val tagline: String?,
    val totalListenTimeMs: Long,
    val booksFinished: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val recentBooks: List<ProfileRecentBook>,
    val publicShelves: List<ProfileShelfSummary>,
) {
    /**
     * Returns true if the user has an uploaded avatar image.
     */
    val hasImageAvatar: Boolean
        get() = avatarType == "image" && !avatarValue.isNullOrBlank()

    /**
     * Returns the user's initials for fallback avatar display.
     */
    val initials: String
        get() =
            displayName
                .split(" ")
                .take(2)
                .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                .joinToString("")
                .ifEmpty { displayName.take(1).uppercase() }

    /**
     * Returns formatted listening time (e.g., "42h 30m").
     */
    val formattedListenTime: String
        get() {
            val hours = totalListenTimeMs / (1000 * 60 * 60)
            val minutes = totalListenTimeMs / (1000 * 60) % 60
            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m"
                else -> "0m"
            }
        }
}

/**
 * A recently listened book shown on a user's profile.
 *
 * @property bookId Book's unique identifier
 * @property title Book title
 * @property coverPath Local path to cover image (optional)
 */
data class ProfileRecentBook(
    val bookId: String,
    val title: String,
    val coverPath: String?,
)

/**
 * Summary of a shelf shown on a user's profile.
 *
 * @property id Shelf unique identifier
 * @property name Shelf display name
 * @property bookCount Number of books in the shelf
 */
data class ProfileShelfSummary(
    val id: String,
    val name: String,
    val bookCount: Int,
)
