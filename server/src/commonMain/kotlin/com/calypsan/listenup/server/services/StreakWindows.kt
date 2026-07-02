package com.calypsan.listenup.server.services

import com.calypsan.listenup.domain.stats.StreakReducer
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Longest consecutive listening-day run among [endedAtMsAscending] — the event-end epoch-ms
 * timestamps — counted in the user's home timezone [tz]. Delegates the day-run logic to the shared
 * [StreakReducer] so windowed, all-time, and client streaks are computed one way and cannot drift.
 *
 * For the windowed leaderboard metric, pass only the events whose end falls inside the trailing
 * window — the result is then the best run achieved within that window (and so caps at its length).
 */
internal fun longestStreakInWindow(
    endedAtMsAscending: List<Long>,
    tz: TimeZone,
): Int {
    if (endedAtMsAscending.isEmpty()) return 0
    val days = endedAtMsAscending.map { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz).date }
    // The longest run is independent of `today`; anchor on the latest day to satisfy the reducer's arg.
    return StreakReducer.reduce(days, today = days.max()).longest
}
