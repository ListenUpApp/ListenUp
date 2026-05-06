@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.BookId
import com.calypsan.listenup.client.data.local.db.GenreDao
import com.calypsan.listenup.client.data.local.db.ListeningEventDao
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/** Milliseconds in a day */
private const val MS_PER_DAY = 86_400_000L

/**
 * Repository for computing listening statistics from local events.
 *
 * Stats are computed entirely from ListeningEventEntity records:
 * - Daily listening: group events by day, sum durations
 * - Total time: sum all event durations in period
 * - Streaks: count consecutive days with listening activity
 * - Genres: per book in the period, query [GenreDao.getGenresForBook] through
 *   the `book_genres` junction to resolve genre names, then aggregate listening
 *   time per genre
 *
 * This enables offline-first stats that update instantly when events are added.
 *
 * @property listeningEventDao DAO for listening events
 * @property genreDao DAO for book_genres junction (genre name resolution per book)
 */
private typealias DomainHomeStats = com.calypsan.listenup.client.domain.repository.HomeStats
private typealias DomainDailyListening = com.calypsan.listenup.client.domain.repository.DailyListening
private typealias DomainGenreListening = com.calypsan.listenup.client.domain.repository.GenreListening

/**
 * [com.calypsan.listenup.client.domain.repository.StatsRepository] implementation that derives
 * weekly home-screen stats (daily totals, current/longest streaks, genre breakdown) entirely
 * from local [ListeningEventEntity] records joined against `book_genres`. Reactive: republishes
 * whenever new events land, so the home screen updates without manual refresh and continues to
 * work fully offline.
 */
class StatsRepositoryImpl(
    private val listeningEventDao: ListeningEventDao,
    private val genreDao: GenreDao,
) : com.calypsan.listenup.client.domain.repository.StatsRepository {
    /**
     * Observe weekly stats (7 days) for the home screen.
     *
     * Automatically recomputes when events change.
     * Uses observeEventsSince (no upper bound) so new events trigger updates.
     */
    override fun observeWeeklyStats(): Flow<DomainHomeStats> {
        val weekAgo = Clock.System.now().toEpochMilliseconds() - (7 * MS_PER_DAY)

        return listeningEventDao.observeEventsSince(weekAgo).map { events ->
            // Filter to exactly 7 days and compute stats with current time
            val now = Clock.System.now().toEpochMilliseconds()
            val filteredEvents = events.filter { it.endedAt <= now }
            computeStats(filteredEvents, weekAgo, now)
        }
    }

    /**
     * Compute all stats from a list of events.
     */
    private suspend fun computeStats(
        events: List<ListeningEventEntity>,
        startMs: Long,
        endMs: Long,
    ): DomainHomeStats {
        logger.debug { "Computing stats from ${events.size} events" }

        val totalTime = events.sumOf { it.durationMs }
        val dailyListening = computeDailyListening(events, startMs, endMs)
        val genreBreakdown = computeGenreBreakdown(events)
        val (currentStreak, longestStreak) = computeStreaks()

        logger.debug {
            "Stats computed: ${totalTime}ms total, streak=$currentStreak, genres=${genreBreakdown.size}"
        }

        return DomainHomeStats(
            totalListenTimeMs = totalTime,
            currentStreakDays = currentStreak,
            longestStreakDays = longestStreak,
            dailyListening = dailyListening,
            genreBreakdown = genreBreakdown.take(3),
        )
    }

    /**
     * Group events by day and compute totals for bar chart.
     *
     * Returns 7 entries, one for each day in the period.
     */
    private fun computeDailyListening(
        events: List<ListeningEventEntity>,
        startMs: Long,
        @Suppress("UnusedParameter") endMs: Long,
    ): List<DomainDailyListening> {
        val tz = TimeZone.currentSystemDefault()

        // Group events by local date
        val byDate =
            events.groupBy { event ->
                formatDate(event.endedAt)
            }

        // Generate 7 days from start date to today (local timezone)
        val today =
            Clock.System
                .now()
                .toLocalDateTime(tz)
                .date
        val startDate = Instant.fromEpochMilliseconds(startMs).toLocalDateTime(tz).date
        val result = mutableListOf<DomainDailyListening>()
        var currentDate = startDate

        while (currentDate <= today) {
            val dateStr = currentDate.toString()
            val dayEvents = byDate[dateStr] ?: emptyList()
            val totalMs = dayEvents.sumOf { it.durationMs }
            val booksListened = dayEvents.map { it.bookId }.distinct().size

            result.add(
                DomainDailyListening(
                    date = dateStr,
                    listenTimeMs = totalMs,
                    booksListened = booksListened,
                ),
            )

            currentDate = currentDate.plus(1, DateTimeUnit.DAY)
        }

        return result
    }

    /**
     * Compute genre breakdown by joining events with book metadata.
     */
    private suspend fun computeGenreBreakdown(events: List<ListeningEventEntity>): List<DomainGenreListening> {
        if (events.isEmpty()) return emptyList()

        // Group by book first
        val byBook = events.groupBy { it.bookId }
        val bookDurations =
            byBook.mapValues { (_, bookEvents) ->
                bookEvents.sumOf { it.durationMs }
            }

        // Get genre info for each book (via book_genres junction)
        val genreDurations = mutableMapOf<String, Long>()

        for ((bookIdStr, durationMs) in bookDurations) {
            val genres =
                genreDao
                    .getGenresForBook(BookId(bookIdStr))
                    .map { it.name }
                    .filter { it.isNotEmpty() }

            if (genres.isEmpty()) {
                // Unknown genre
                genreDurations["Unknown"] = (genreDurations["Unknown"] ?: 0L) + durationMs
            } else {
                // Distribute time across all genres (could also just use first)
                val perGenre = durationMs / genres.size
                for (genre in genres) {
                    genreDurations[genre] = (genreDurations[genre] ?: 0L) + perGenre
                }
            }
        }

        // Sort by duration and compute percentages
        val totalMs = genreDurations.values.sum()
        return genreDurations
            .entries
            .sortedByDescending { it.value }
            .map { (genre, durationMs) ->
                DomainGenreListening(
                    genreSlug = genre.lowercase().replace(" ", "-"),
                    genreName = genre,
                    listenTimeMs = durationMs,
                    percentage = if (totalMs > 0) durationMs.toDouble() / totalMs * 100 else 0.0,
                )
            }
    }

    /**
     * Compute current and longest streak from all listening events.
     *
     * A streak is defined as consecutive calendar days with listening activity.
     * Uses all events (not just weekly) for accurate streak tracking.
     */
    private suspend fun computeStreaks(): Pair<Int, Int> {
        // Get all days with activity (going back far enough for longest streak)
        val ninetyDaysAgo = Clock.System.now().toEpochMilliseconds() - (90 * MS_PER_DAY)
        val daysWithActivity = listeningEventDao.getDistinctDaysWithActivity(ninetyDaysAgo)

        if (daysWithActivity.isEmpty()) {
            return Pair(0, 0)
        }

        val todayStart = Clock.System.now().toEpochMilliseconds() / MS_PER_DAY * MS_PER_DAY
        val yesterdayStart = todayStart - MS_PER_DAY

        // Convert to set for O(1) lookup
        val activeDays = daysWithActivity.toSet()

        // Compute current streak (must include today or yesterday)
        var currentStreak = 0
        if (todayStart in activeDays || yesterdayStart in activeDays) {
            // Start from today if active, otherwise yesterday
            var day = if (todayStart in activeDays) todayStart else yesterdayStart
            while (day in activeDays) {
                currentStreak++
                day -= MS_PER_DAY
            }
        }

        // Compute longest streak
        var longestStreak = 0
        var streakCount = 0
        var prevDay: Long? = null

        // Days are sorted descending, so iterate in reverse for chronological order
        for (day in daysWithActivity.reversed()) {
            if (prevDay == null || day - prevDay == MS_PER_DAY) {
                // Consecutive day
                streakCount++
            } else {
                // Gap - start new streak
                longestStreak = maxOf(longestStreak, streakCount)
                streakCount = 1
            }
            prevDay = day
        }
        longestStreak = maxOf(longestStreak, streakCount)

        return Pair(currentStreak, longestStreak)
    }

    /**
     * Format epoch ms as YYYY-MM-DD string using local timezone.
     */
    private fun formatDate(epochMs: Long): String =
        Instant
            .fromEpochMilliseconds(epochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
            .toString()
}
