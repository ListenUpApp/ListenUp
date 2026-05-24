@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.domain.leaderboard

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * The four time-range periods on the Discover leaderboard. Each computes its
 * `(startMs, endMs)` window in the user's local timezone — DST-safe via
 * `kotlinx.datetime` LocalDate arithmetic.
 *
 * Also reused by [com.calypsan.listenup.client.data.repository.StatsRepositoryImpl]
 * for the Home Stats 7-day window. Single source of truth for window math;
 * tested standalone in `LeaderboardPeriodTest`.
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

    /** Last 7 days ending at [now], day-aligned in [tz]. */
    data object Week : LeaderboardPeriod {
        override fun bounds(
            now: Instant,
            tz: TimeZone,
        ): Pair<Long, Long> {
            val today = now.toLocalDateTime(tz).date
            val startDate = today.minus(DatePeriod(days = 6))
            return startDate.atStartOfDayIn(tz).toEpochMilliseconds() to now.toEpochMilliseconds()
        }
    }

    /** Current calendar month in [tz]. */
    data object Month : LeaderboardPeriod {
        override fun bounds(
            now: Instant,
            tz: TimeZone,
        ): Pair<Long, Long> {
            val today = now.toLocalDateTime(tz).date
            val firstOfMonth = LocalDate(today.year, today.month, 1)
            return firstOfMonth.atStartOfDayIn(tz).toEpochMilliseconds() to now.toEpochMilliseconds()
        }
    }

    /** Current calendar year in [tz]. */
    data object Year : LeaderboardPeriod {
        override fun bounds(
            now: Instant,
            tz: TimeZone,
        ): Pair<Long, Long> {
            val today = now.toLocalDateTime(tz).date
            val firstOfYear = LocalDate(today.year, 1, 1)
            return firstOfYear.atStartOfDayIn(tz).toEpochMilliseconds() to now.toEpochMilliseconds()
        }
    }

    /** All recorded history. */
    data object AllTime : LeaderboardPeriod {
        override fun bounds(
            now: Instant,
            tz: TimeZone,
        ): Pair<Long, Long> = 0L to Long.MAX_VALUE
    }
}
