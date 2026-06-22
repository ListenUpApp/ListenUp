package com.calypsan.listenup.server.services

import kotlin.math.max
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Longest consecutive listening-day run among [endedAtMsAscending] — the event-end epoch-ms
 * timestamps, **already sorted ascending** — counted in the user's home timezone [tz].
 *
 * Each event is reduced to the calendar date of its end in [tz]; a run grows while dates advance by
 * exactly one day, restarts on any gap, and same-day events don't extend it. Returns the maximum run
 * length, or 0 when there are no events. The walk mirrors the all-time streak math in
 * [UserStatsBackfillService] / [UserStatsUpdater], so windowed and all-time streaks stay consistent.
 *
 * For the windowed leaderboard metric, pass only the events whose end falls inside the trailing
 * window — the result is then the best run achieved within that window (and so caps at its length).
 */
internal fun longestStreakInWindow(
    endedAtMsAscending: List<Long>,
    tz: TimeZone,
): Int {
    var lastDate: LocalDate? = null
    var current = 0
    var longest = 0
    for (endedAt in endedAtMsAscending) {
        val date =
            Instant
                .fromEpochMilliseconds(endedAt)
                .toLocalDateTime(tz)
                .date
        current =
            when {
                lastDate == null -> 1
                date == lastDate -> current.coerceAtLeast(1)
                date == lastDate.plus(DatePeriod(days = 1)) -> current + 1
                else -> 1
            }
        longest = max(longest, current)
        lastDate = date
    }
    return longest
}
