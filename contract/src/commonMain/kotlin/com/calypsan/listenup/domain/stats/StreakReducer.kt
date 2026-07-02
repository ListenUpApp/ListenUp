package com.calypsan.listenup.domain.stats

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * A user's current and longest listening streaks, in whole days.
 *
 * @property current The consecutive-day run ending on the most recent listening day, or 0 if that
 *   day is older than yesterday (the streak has lapsed).
 * @property longest The maximum consecutive-day run across the user's whole listening history.
 */
data class Streaks(
    val current: Int,
    val longest: Int,
)

/**
 * The single, pure streak calculation shared by the server (per-event derive, bulk backfill, and the
 * windowed leaderboard projection) and the client Home screen. Consolidating the day-run logic here
 * means every surface reports the same streak from the same primitive — no per-surface reimplementation
 * that can drift.
 *
 * A "streak day" is any calendar day on which the user listened at least once. The reducer speaks only
 * in calendar dates: resolving event timestamps to a [LocalDate] in the correct timezone is the
 * caller's responsibility (device timezone on the client, the user's home timezone on the server).
 *
 * The reducer is **order- and duplicate-independent**: it distinct-sorts its input, so callers may pass
 * listening days in any order and with repeats (multiple sessions on one day collapse to a single
 * streak day).
 */
object StreakReducer {
    /**
     * Reduce [listeningDays] (the calendar days the user listened, any order, duplicates allowed) to
     * the [Streaks] as of [today].
     *
     * [Streaks.longest] is the longest consecutive-day run anywhere in the history. [Streaks.current]
     * is the run ending on the most recent listening day, but only when that day is [today] or the day
     * before — otherwise the streak has lapsed and it is 0. Empty input yields `Streaks(0, 0)`.
     */
    fun reduce(
        listeningDays: List<LocalDate>,
        today: LocalDate,
    ): Streaks {
        if (listeningDays.isEmpty()) return Streaks(current = 0, longest = 0)

        val days = listeningDays.distinct().sorted()

        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in days) {
            run = if (previous != null && day == previous.plus(DatePeriod(days = 1))) run + 1 else 1
            longest = maxOf(longest, run)
            previous = day
        }

        val mostRecent = days.last()
        val current =
            if (mostRecent == today || mostRecent == today.minus(DatePeriod(days = 1))) {
                val present = days.toSet()
                var count = 0
                var cursor = mostRecent
                while (cursor in present) {
                    count++
                    cursor = cursor.minus(DatePeriod(days = 1))
                }
                count
            } else {
                0
            }

        return Streaks(current = current, longest = longest)
    }
}
