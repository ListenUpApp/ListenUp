package com.calypsan.listenup.client.domain.leaderboard

/**
 * A leaderboard snapshot containing three category rankings for one [LeaderboardPeriod].
 * All three lists are computed in the same DB read, so switching category on the UI
 * is a pure state transformation — no upstream re-fetch.
 *
 * For Week/Month/Year, the [time] ranking is computed over `listening_events`
 * within the period bounds; [books] and [streak] lists are empty since per-period
 * book/streak rankings require domain-specific math that is out of scope here. Time is
 * the headline ranking for bounded periods.
 *
 * For AllTime, all three lists are sourced from `user_stats`.
 *
 * Profile data (displayName) is LEFT-JOINed — missing profiles fall back to
 * `displayName = "User"` (rendered by [UserAvatar] as initials).
 */
data class LeaderboardSnapshot(
    val time: List<LeaderboardEntry>,
    val books: List<LeaderboardEntry>,
    val streak: List<LeaderboardEntry>,
)

/**
 * One entry in a leaderboard ranking. [rank] is dense — ties share a rank
 * (`[1, 1, 1, 4, 5]`, not `[1, 2, 3, 4, 5]`).
 *
 * Avatar resolution happens at the UI layer via `UserAvatar(userId = entry.userId)`;
 * the entry carries only identity and stats.
 */
data class LeaderboardEntry(
    val rank: Int,
    val userId: String,
    val displayName: String,
    val totalSeconds: Long,
    val booksFinished: Int,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
)

/** Which category the user is currently viewing on the leaderboard tabs. */
enum class LeaderboardCategory { Time, Books, Streak }
