@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.domain.leaderboard

import com.calypsan.listenup.domain.stats.StatsWindow
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * The four time-range periods on the Discover leaderboard. The bounded periods are the shared
 * [StatsWindow]s — today plus the previous days back to local midnight in [bounds]' timezone — so the
 * Leaderboard's Week is the same week Home's "This week" counts, and both match the server's
 * `user_stats` / `public_profiles` windows. DST-safe via `kotlinx.datetime` LocalDate arithmetic.
 */
sealed interface LeaderboardPeriod {
    /**
     * Compute the `(startMs, endMs)` epoch-millisecond window for this period.
     *
     * @param now The reference instant (current time).
     * @param tz The user's local timezone; day-boundaries are resolved in this zone.
     * @return A pair `(startMs, endMs)` where `startMs` is inclusive and `endMs`
     *   equals `now.toEpochMilliseconds()` for bounded periods.
     */
    fun bounds(
        now: Instant,
        tz: TimeZone,
    ): Pair<Long, Long>

    /** Today and the previous six days in [tz] — [StatsWindow.Week]. */
    data object Week : LeaderboardPeriod {
        override fun bounds(
            now: Instant,
            tz: TimeZone,
        ): Pair<Long, Long> = StatsWindow.Week.startMs(now, tz) to now.toEpochMilliseconds()
    }

    /** Today and the previous 29 days in [tz] — [StatsWindow.Month]. */
    data object Month : LeaderboardPeriod {
        override fun bounds(
            now: Instant,
            tz: TimeZone,
        ): Pair<Long, Long> = StatsWindow.Month.startMs(now, tz) to now.toEpochMilliseconds()
    }

    /** Today and the previous 364 days in [tz] — [StatsWindow.Year]. */
    data object Year : LeaderboardPeriod {
        override fun bounds(
            now: Instant,
            tz: TimeZone,
        ): Pair<Long, Long> = StatsWindow.Year.startMs(now, tz) to now.toEpochMilliseconds()
    }

    /** All recorded history. */
    data object AllTime : LeaderboardPeriod {
        override fun bounds(
            now: Instant,
            tz: TimeZone,
        ): Pair<Long, Long> = 0L to Long.MAX_VALUE
    }
}
