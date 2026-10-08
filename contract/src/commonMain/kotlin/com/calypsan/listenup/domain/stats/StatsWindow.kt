package com.calypsan.listenup.domain.stats

import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * The one definition of a listening-stats window, shared by the client (Home's "This week") and the
 * server (`user_stats` and the `public_profiles` leaderboard), so the same listening can never add up
 * to two different numbers on two screens.
 *
 * A window of [days] is **today plus the previous `days - 1` calendar days**, starting at local
 * midnight in the listener's timezone — the device zone on the client, the user's home timezone
 * (`users.timezone`, UTC when unknown) on the server. It ends at the moment it is evaluated. Because
 * the start is a calendar date resolved with `atStartOfDayIn`, the window stays correct across DST
 * transitions, and it only moves at local midnight: absent new listening, a total changes once a day.
 *
 * A listening span counts **whole** toward the window when it ends inside it, even if it began before
 * the window opened — see [secondsCounted]. That matches Home's per-day bars, which attribute each
 * session to the day it ended, so the bars always sum to the total.
 *
 * @property days How many calendar days the window covers, today included.
 */
enum class StatsWindow(
    val days: Int,
) {
    /** Today and the previous six days — Home's "This week" and the Leaderboard's Week. */
    Week(days = 7),

    /** Today and the previous 29 days — the Leaderboard's Month. */
    Month(days = 30),

    /** Today and the previous 364 days — the Leaderboard's Year. */
    Year(days = 365),
    ;

    /**
     * Epoch-ms start of this window as of [now]: local midnight in [timeZone] on the window's first day.
     */
    fun startMs(
        now: Instant,
        timeZone: TimeZone,
    ): Long {
        val today = now.toLocalDateTime(timeZone).date
        return today.minus(DatePeriod(days = days - 1)).atStartOfDayIn(timeZone).toEpochMilliseconds()
    }

    /** The counting rule every window shares. */
    companion object {
        private const val MILLIS_PER_SECOND = 1_000L

        /**
         * Wall-clock seconds a span from [startedAtMs] to [endedAtMs] contributes to a window that
         * starts at [windowStartMs]: the whole span when it ended at or after the start, else nothing.
         */
        fun secondsCounted(
            startedAtMs: Long,
            endedAtMs: Long,
            windowStartMs: Long,
        ): Long = if (endedAtMs >= windowStartMs) (endedAtMs - startedAtMs) / MILLIS_PER_SECOND else 0L
    }
}
