package com.calypsan.listenup.client.domain

/**
 * Aggregated personal listening stats for the last 7 days.
 *
 * Backed by the `listening_events` table (for day buckets and genre breakdown)
 * and `user_stats` (for server-maintained streak counters). The ViewModel reads
 * this type from [com.calypsan.listenup.client.domain.repository.StatsRepository]
 * and maps it into UI state — no further aggregation needed at presentation layer.
 */
data class WeeklyStats(
    /** 7 buckets ordered by recency: index 0 = today, index 6 = six days ago. */
    val dailyBuckets: List<DayBucket>,
    /** Current consecutive-day streak, as maintained by the server in `user_stats`. */
    val currentStreakDays: Int,
    /** All-time longest streak, as maintained by the server in `user_stats`. */
    val longestStreakDays: Int,
    /** Up to 3 genres ranked by total listening seconds this week, descending. */
    val topGenres: List<GenreShare>,
    /** Sum of all listening seconds recorded within the 7-day window. */
    val totalSecondsThisWeek: Long,
) {
    /**
     * True when the user has never listened to anything — distinct empty state
     * shown before any history exists. Distinguished from a user who listened
     * in the past but not this week.
     */
    val isEverEmpty: Boolean get() = totalSecondsThisWeek == 0L && longestStreakDays == 0

    companion object {
        /** Safe zero value emitted while no user is signed in or before events load. */
        fun empty(): WeeklyStats =
            WeeklyStats(
                dailyBuckets = List(7) { offset -> DayBucket(dayOffsetFromToday = offset, totalSeconds = 0L) },
                currentStreakDays = 0,
                longestStreakDays = 0,
                topGenres = emptyList(),
                totalSecondsThisWeek = 0L,
            )
    }
}

/**
 * Listening total for a single calendar day.
 *
 * [dayOffsetFromToday] is 0 for today, 1 for yesterday, up to 6 for six days ago.
 * This ordering matches a "today first" bar chart layout.
 */
data class DayBucket(
    val dayOffsetFromToday: Int,
    val totalSeconds: Long,
)

/** Genre total within the 7-day window, used for the genre breakdown chart. */
data class GenreShare(
    val genreName: String,
    val totalSeconds: Long,
)
