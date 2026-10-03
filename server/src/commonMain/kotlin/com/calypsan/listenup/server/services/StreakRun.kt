package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Every instant that makes its calendar day a streak day: when each listening event ended
 * ([listeningEndedAtMs]), when each book's progress last moved, and when each book was finished. The
 * one definition of "a day the user listened" — [deriveUserStats] counts the streak from it and
 * [currentStreakRun] dates the milestones from it, so the two can't disagree about which days count.
 */
internal suspend fun streakDayInstantsMs(
    sql: ListenUpDatabase,
    userId: String,
    listeningEndedAtMs: List<Long>,
): List<Long> {
    val instants = ArrayList(listeningEndedAtMs)
    suspendTransaction(sql) {
        instants += sql.playbackPositionsQueries.selectLastPlayedAtForUser(userId).executeAsList()
        instants += sql.bookReadsQueries.finishedAtForUser(userId).executeAsList()
    }
    return instants
}

/** The calendar day [epochMs] falls on in [tz]. */
internal fun localDayOf(
    epochMs: Long,
    tz: TimeZone,
): LocalDate = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(tz).date

/**
 * The streak a user is on right now, as a run of consecutive calendar days in their home timezone.
 *
 * A run is identified by the day it began: a streak that was only *reported* broken — because a day's
 * listening hadn't synced yet — has the same start day once that listening arrives, so it is the same
 * run. Only a real gap starts a new one.
 *
 * @property startDay the first day of the run.
 * @property startMs local midnight at the start of [startDay], in the user's home timezone.
 */
internal class StreakRun(
    val startDay: LocalDate,
    val startMs: Long,
    private val firstInstantByDay: Map<LocalDate, Long>,
) {
    /**
     * When the run reached [days] long: the first streak-day instant on its [days]th day — the moment
     * that day began to count. `null` if the run is shorter than that.
     */
    fun reachedLengthAtMs(days: Int): Long? = firstInstantByDay[startDay.plus(DatePeriod(days = days - 1))]
}

/**
 * Resolves the run behind a current streak of [currentStreakDays] days — the value [deriveUserStats]
 * just computed from the same primitives, under the same per-user lock. The run ends on the most recent
 * streak day and reaches back [currentStreakDays] days from it, exactly as
 * [com.calypsan.listenup.domain.stats.StreakReducer] counts it. `null` when there is no current streak.
 */
internal suspend fun currentStreakRun(
    sql: ListenUpDatabase,
    userId: String,
    currentStreakDays: Int,
    tz: TimeZone,
): StreakRun? {
    if (currentStreakDays <= 0) return null
    val listeningEndedAtMs =
        suspendTransaction(sql) {
            sql.listeningEventsQueries.selectForUserOrderedByEndedAt(userId).executeAsList().map { it.ended_at }
        }
    val firstInstantByDay =
        streakDayInstantsMs(sql, userId, listeningEndedAtMs)
            .groupBy { localDayOf(it, tz) }
            .mapValues { (_, instants) -> instants.min() }
    val mostRecentDay = firstInstantByDay.keys.maxOrNull() ?: return null
    val startDay = mostRecentDay.minus(DatePeriod(days = currentStreakDays - 1))
    return StreakRun(
        startDay = startDay,
        startMs = startDay.atStartOfDayIn(tz).toEpochMilliseconds(),
        firstInstantByDay = firstInstantByDay,
    )
}
