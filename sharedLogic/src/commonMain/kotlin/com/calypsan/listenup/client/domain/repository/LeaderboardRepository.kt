package com.calypsan.listenup.client.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for leaderboard operations.
 *
 * Provides access to listening leaderboards computed from local data
 * with API support for historical all-time data.
 *
 * Part of the domain layer - implementations live in the data layer.
 */
interface LeaderboardRepository {
    /**
     * Observe leaderboard entries for a given period and category.
     * Entries are automatically sorted by the category value.
     *
     * @param period Time period to aggregate
     * @param category Ranking category (time, books, streak)
     * @param limit Maximum number of entries
     * @return Flow of leaderboard entries
     */
    fun observeLeaderboard(
        period: LeaderboardPeriod,
        category: LeaderboardCategory,
        limit: Int = 10,
    ): Flow<List<LeaderboardEntry>>

    /**
     * Observe community aggregate stats for a given period.
     *
     * @param period Time period to aggregate
     * @return Flow of community stats
     */
    fun observeCommunityStats(period: LeaderboardPeriod): Flow<CommunityStats>

    /**
     * Fetch and cache user stats for All-time leaderboard.
     * Called when the user_stats cache is empty.
     * Returns true if stats were fetched successfully.
     */
    suspend fun fetchAndCacheUserStats(): Boolean

    /**
     * Check if user stats cache is empty.
     */
    suspend fun isUserStatsCacheEmpty(): Boolean
}

/**
 * Time period for leaderboard calculation.
 */
enum class LeaderboardPeriod {
    DAY,
    WEEK,
    MONTH,
    YEAR,
    ALL,
}

/**
 * Ranking category for leaderboard sorting.
 */
enum class LeaderboardCategory {
    TIME,
    BOOKS,
    STREAK,
}

/**
 * A single entry on the leaderboard.
 */
data class LeaderboardEntry(
    val rank: Int,
    val userId: String,
    val displayName: String,
    val avatarColor: String,
    val avatarType: String,
    val avatarValue: String?,
    val timeMs: Long,
    val booksCount: Int,
    val streakDays: Int,
    val isCurrentUser: Boolean,
) {
    /**
     * Get the value for a specific category.
     */
    fun valueFor(category: LeaderboardCategory): Long =
        when (category) {
            LeaderboardCategory.TIME -> timeMs
            LeaderboardCategory.BOOKS -> booksCount.toLong()
            LeaderboardCategory.STREAK -> streakDays.toLong()
        }

    /**
     * Format the value as a human-readable label.
     */
    fun labelFor(category: LeaderboardCategory): String =
        when (category) {
            LeaderboardCategory.TIME -> formatDuration(timeMs)
            LeaderboardCategory.BOOKS -> "$booksCount books"
            LeaderboardCategory.STREAK -> "$streakDays days"
        }
}

/**
 * Community aggregate stats.
 */
data class CommunityStats(
    val totalTimeMs: Long,
    val totalBooks: Int,
    val activeUsers: Int,
) {
    val totalTimeLabel: String get() = formatDuration(totalTimeMs)
}

/**
 * Format milliseconds as human-readable duration.
 * Examples: "2h 30m", "45m", "1h"
 */
private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    return when {
        hours == 0L -> "${minutes}m"
        minutes == 0L -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}
