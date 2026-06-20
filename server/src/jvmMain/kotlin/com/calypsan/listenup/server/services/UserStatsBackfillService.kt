package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Rebuilds the materialized `user_stats` row from scratch by replaying all
 * `listening_events` for a user, plus counting `playback_positions` where
 * `finished = true`.
 *
 * Useful when the stats schema changes, after a bug, or after restoring from
 * backup. Idempotent — running it twice produces the same result.
 *
 * **Engines.** Event and position reads go through [sql] (the SQLDelight [ListenUpDatabase]);
 * the day-boundary timezone is read from the still-Exposed `users` table via [db]. The backfill
 * runs as a standalone admin/import operation (not nested inside another write transaction), so
 * its reads and the [UserStatsRepository.upsert] write are independent transactions.
 */
class UserStatsBackfillService(
    private val sql: ListenUpDatabase,
    private val db: Database,
    private val userStatsRepo: UserStatsRepository,
    private val clock: Clock = Clock.System,
) {
    /**
     * Rebuilds the `user_stats` row for [userId] from the raw event and position tables.
     * Replaces any pre-existing row with the fully recomputed values.
     */
    suspend fun backfillFor(userId: String) {
        val nowMs = clock.now().toEpochMilliseconds()

        // 1. Read all listening_events for this user, ordered by endedAt ascending.
        val events =
            suspendTransaction(sql) {
                sql.listeningEventsQueries
                    .selectForUserOrderedByEndedAt(userId)
                    .executeAsList()
            }

        // 2. Walk events to compute totals, distinct books, and streaks.
        //    All day-boundary math uses the user's home timezone — one consistent frame
        //    per user. The per-event tz field is ignored here because it can be "UTC" for
        //    ABS imports and mixed-frame for travelers, producing wrong streaks.
        val userTz = db.homeTimeZone(userId)
        var totalAllTime = 0L
        val distinctBooks = mutableSetOf<String>()
        var lastDate: LocalDate? = null
        var currentStreak = 0
        var longestStreak = 0

        for (event in events) {
            val wallSeconds = (event.ended_at - event.started_at) / 1_000L
            totalAllTime += wallSeconds
            distinctBooks.add(event.book_id)

            val eventDate =
                Instant
                    .fromEpochMilliseconds(event.ended_at)
                    .toLocalDateTime(userTz)
                    .date

            currentStreak =
                when {
                    lastDate == null -> 1
                    eventDate == lastDate -> currentStreak.coerceAtLeast(1)
                    eventDate == lastDate.plus(DatePeriod(days = 1)) -> currentStreak + 1
                    else -> 1
                }
            longestStreak = max(longestStreak, currentStreak)
            lastDate = eventDate
        }

        // 3. Rolling-window sums against nowMs.
        val cutoff7 = nowMs - 7 * 86_400_000L
        val cutoff30 = nowMs - 30 * 86_400_000L
        var last7 = 0L
        var last30 = 0L
        for (event in events) {
            val endedAtMs = event.ended_at
            val wallSeconds = (endedAtMs - event.started_at) / 1_000L
            if (endedAtMs >= cutoff7) last7 += wallSeconds
            if (endedAtMs >= cutoff30) last30 += wallSeconds
        }

        // 4. Count finished positions (non-deleted) for this user.
        val booksFinished =
            suspendTransaction(sql) {
                sql.playbackPositionsQueries
                    .countFinishedForUser(userId)
                    .executeAsOne()
                    .toInt()
            }

        // 5. The walk's currentStreak is the streak as of lastDate; the *current* streak is only that
        // value if the last event was today or yesterday — otherwise the streak has lapsed. "Today"
        // is resolved in the user's home timezone (same frame as the walk above), not the per-event tz.
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(userTz).date
        val currentStreakAsOfToday =
            when {
                lastDate == null -> 0
                lastDate == today || lastDate == today.minus(DatePeriod(days = 1)) -> currentStreak
                else -> 0
            }

        // 6. Upsert the rebuilt row. The substrate assigns revision and timestamps.
        val rebuilt =
            UserStatsSyncPayload(
                id = userId,
                totalSecondsAllTime = totalAllTime,
                totalSecondsLast7Days = last7,
                totalSecondsLast30Days = last30,
                booksStarted = distinctBooks.size,
                booksFinished = booksFinished,
                currentStreakDays = currentStreakAsOfToday,
                longestStreakDays = longestStreak,
                lastEventDate = lastDate?.toString(),
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            )
        userStatsRepo.upsert(rebuilt, clientOpId = null, userId = userId)
    }
}
