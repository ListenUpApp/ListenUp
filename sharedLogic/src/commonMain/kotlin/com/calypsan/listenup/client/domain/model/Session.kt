package com.calypsan.listenup.client.domain.model

/**
 * Summary of one of the current user's reading sessions for a book.
 *
 * Used to display the user's own reading history on the book readers screen.
 *
 * @property id Session unique identifier
 * @property startedAt When the session started (ISO timestamp)
 * @property finishedAt When the session finished (null if incomplete)
 * @property isCompleted Whether this session represents a complete read
 * @property listenTimeMs Total listen time for this session in milliseconds
 */
data class SessionSummary(
    val id: String,
    val startedAt: String,
    val finishedAt: String? = null,
    val isCompleted: Boolean,
    val listenTimeMs: Long,
)

/**
 * Result of fetching book readers information.
 *
 * Contains both the current user's reading history and information
 * about other users who have read or are reading the book.
 *
 * @property yourSessions The current user's reading sessions for this book
 * @property otherReaders Information about other users reading/who read this book
 * @property totalReaders Total number of unique readers (including current user)
 * @property totalCompletions Total number of times the book has been completed
 */
data class BookReadersResult(
    val yourSessions: List<SessionSummary>,
    val otherReaders: List<ReaderInfo>,
    val totalReaders: Int,
    val totalCompletions: Int,
)

/**
 * Domain model representing a user currently reading/listening to a book.
 *
 * Used to display "who's listening" on book detail screens,
 * showing other users currently engaged with the same book.
 *
 * @property userId User's unique identifier
 * @property displayName User's display name
 * @property avatarType Avatar type ("auto" or "image")
 * @property avatarValue Path to avatar image (when type is "image")
 * @property avatarColor User's avatar background color (hex format)
 * @property isCurrentlyReading Whether the user is actively reading now
 * @property currentProgress Reading progress as a percentage (0.0 - 1.0)
 * @property startedAt When the user started reading (ISO timestamp)
 * @property finishedAt When the user finished (null if not finished)
 * @property lastActivityAt When the user last interacted (ISO timestamp) - used for sorting
 * @property completionCount Number of times the user has completed this book
 * @property isCurrentUser Whether this represents the currently logged-in user
 */
data class ReaderInfo(
    val userId: String,
    val displayName: String,
    val avatarType: String = "auto",
    val avatarValue: String? = null,
    val avatarColor: String,
    val isCurrentlyReading: Boolean,
    val currentProgress: Double,
    val startedAt: String,
    val finishedAt: String? = null,
    val lastActivityAt: String,
    val completionCount: Int,
    val isCurrentUser: Boolean = false,
) {
    /**
     * Returns the user's initials for avatar display.
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
     * Returns true if the user has an uploaded avatar image.
     */
    val hasImageAvatar: Boolean
        get() = avatarType == "image" && !avatarValue.isNullOrBlank()

    /**
     * Returns progress as a percentage string (e.g., "75%").
     */
    val formattedProgress: String
        get() = "${(currentProgress * 100).toInt()}%"
}
