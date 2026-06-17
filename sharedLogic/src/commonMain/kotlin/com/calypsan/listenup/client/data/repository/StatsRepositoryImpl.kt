@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.local.db.UserStatsDao
import com.calypsan.listenup.client.data.local.db.UserStatsEntity
import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.WeeklyStats
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.StatsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val MIDNIGHT_PULSE_DELAY_MS = 60_000L

/**
 * [StatsRepository] implementation that derives weekly home-screen stats
 * (daily buckets, streaks, genre breakdown) entirely from local Room data.
 *
 * Reactive: republishes whenever new events land or `user_stats` is updated
 * by sync, so the home screen updates without manual refresh and works fully
 * offline.
 *
 * Streak counters are read directly from [UserStatsDao] rather than recomputed
 * locally — the server is authoritative for streak math (timezone-aware, handles
 * cross-device sessions). Genre seconds use wall-clock time (`endedAt - startedAt`)
 * so fast-forwarded or slow-mo sessions don't inflate genre totals.
 *
 * @param listeningEventDao DAO for reactive window queries.
 * @param userStatsDao DAO for server-maintained streak counters.
 * @param genreDao DAO for bulk book→genre name resolution.
 * @param authSession Source of the current user's ID via the auth state flow.
 * @param clock Injected for deterministic testing; defaults to [Clock.System].
 * @param timeZone Factory for the user's current timezone; defaults to the
 *   system default. Injected for deterministic timezone testing.
 * @param ticker Per-minute trigger that causes window bounds to be recomputed.
 *   Defaults to [midnightPulse]; injectable for testing so tests can pass
 *   `flowOf(Unit)` and avoid an infinite `delay` loop.
 */
internal class StatsRepositoryImpl(
    private val listeningEventDao: ListeningEventDao,
    private val userStatsDao: UserStatsDao,
    private val genreDao: GenreDao,
    private val authSession: AuthSession,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val ticker: Flow<Unit> = midnightPulse(),
) : StatsRepository {
    override fun observeWeeklyStats(): Flow<WeeklyStats> =
        // Re-evaluate the window whenever the user changes or the clock ticks
        // past a minute boundary (so day buckets roll over at local midnight).
        authSession.authState
            .map { state ->
                when (state) {
                    is AuthState.Authenticated -> state.userId.value
                    else -> null
                }
            }.combine(ticker) { userId, _ -> userId }
            .flatMapLatest { userId ->
                if (userId == null) return@flatMapLatest flowOf(WeeklyStats.empty())
                val tz = timeZone()
                val (startMs, endMs) = LeaderboardPeriod.Week.bounds(clock.now(), tz)
                combine(
                    listeningEventDao.observeWithinWindow(userId, startMs, endMs),
                    userStatsDao.observe(userId),
                ) { events, stats -> aggregate(events, stats, tz) }
            }

    private suspend fun aggregate(
        events: List<ListeningEventEntity>,
        stats: UserStatsEntity?,
        tz: TimeZone,
    ): WeeklyStats {
        if (events.isEmpty() && stats == null) return WeeklyStats.empty()

        val today = clock.now().toLocalDateTime(tz).date
        val buckets = MutableList(7) { offset -> DayBucket(dayOffsetFromToday = offset, totalSeconds = 0L) }
        var totalSeconds = 0L

        for (event in events) {
            val eventDate = Instant.fromEpochMilliseconds(event.endedAt).toLocalDateTime(tz).date
            val offset = (today.toEpochDays() - eventDate.toEpochDays()).toInt()
            if (offset in 0..6) {
                val wallSeconds = (event.endedAt - event.startedAt) / 1000L
                buckets[offset] = buckets[offset].copy(totalSeconds = buckets[offset].totalSeconds + wallSeconds)
                totalSeconds += wallSeconds
            }
        }

        val bookIds = events.map { it.bookId }.toSet()
        val genresByBook = if (bookIds.isEmpty()) emptyMap() else genreDao.getGenresForBooks(bookIds)
        val genreSeconds = mutableMapOf<String, Long>()
        for (event in events) {
            val wallSeconds = (event.endedAt - event.startedAt) / 1000L
            val genres = genresByBook[event.bookId].orEmpty()
            for (g in genres) {
                genreSeconds[g] = (genreSeconds[g] ?: 0L) + wallSeconds
            }
        }
        val topGenres =
            genreSeconds.entries
                .sortedByDescending { it.value }
                .take(3)
                .map { GenreShare(it.key, it.value) }

        return WeeklyStats(
            dailyBuckets = buckets,
            currentStreakDays = stats?.currentStreakDays ?: 0,
            longestStreakDays = stats?.longestStreakDays ?: 0,
            topGenres = topGenres,
            totalSecondsThisWeek = totalSeconds,
        )
    }
}

/**
 * Emits [Unit] once per minute. Combined with the auth-state flow, this
 * causes the window bounds to be recomputed each minute so the active day
 * bucket rolls over at local midnight without any manual refresh.
 */
private fun midnightPulse(): Flow<Unit> =
    flow {
        emit(Unit)
        while (true) {
            delay(MIDNIGHT_PULSE_DELAY_MS)
            emit(Unit)
        }
    }
