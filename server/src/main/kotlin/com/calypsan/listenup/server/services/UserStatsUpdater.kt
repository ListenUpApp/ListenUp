package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.server.db.ListeningEventTable
import kotlin.math.max
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Maintains the materialized `user_stats` row incrementally.
 *
 * Called from inside the listening-event upsert path (via [onListeningEvent])
 * and from `PlaybackPositionRepository.recordPosition` when `finished` flips
 * false → true (via [onPositionFinishedFlip]). Both writes are atomic with their
 * source row, so stats are never observably inconsistent with the events /
 * positions they aggregate.
 *
 * Window math (last 7 / 30 days) is recomputed via indexed `SUM`s over
 * [ListeningEventTable]; cheap with the (`user_id`, `ended_at`) index.
 */
class UserStatsUpdater(
    private val db: Database,
    private val userStatsRepo: UserStatsRepository,
    private val clock: Clock = Clock.System,
) {
    /**
     * Increment-and-upsert called after a `listening_events` row commits.
     * May run inside an existing Exposed transaction (nested transactions
     * short-circuit in Exposed JDBC).
     */
    suspend fun onListeningEvent(
        userId: String,
        event: ListeningEventSyncPayload,
    ) {
        val wallSeconds = (event.endedAt - event.startedAt) / 1_000L
        val tz = TimeZone.of(event.tz)
        val eventInstant = Instant.fromEpochMilliseconds(event.endedAt)
        val eventDateStr = eventInstant.toLocalDateTime(tz).date.toString()

        val existing = userStatsRepo.getForUser(userId)

        val isFirstEventForBook = !hasOtherEventForBook(userId, event.bookId, excludingId = event.id)
        val newCurrentStreak = newStreakValue(existing?.lastEventDate, eventDateStr, existing?.currentStreakDays ?: 0)
        val last7 = sumWindowSeconds(userId, days = 7, asOfMs = event.endedAt)
        val last30 = sumWindowSeconds(userId, days = 30, asOfMs = event.endedAt)

        val base = existing ?: emptyStatsFor(userId)
        val updated =
            base.copy(
                totalSecondsAllTime = base.totalSecondsAllTime + wallSeconds,
                totalSecondsLast7Days = last7,
                totalSecondsLast30Days = last30,
                booksStarted = base.booksStarted + if (isFirstEventForBook) 1 else 0,
                currentStreakDays = newCurrentStreak,
                longestStreakDays = max(base.longestStreakDays, newCurrentStreak),
                lastEventDate = eventDateStr,
            )
        userStatsRepo.upsert(updated, clientOpId = null, userId = userId)
    }

    /**
     * Called when a `playback_positions` row's `finished` flips false → true.
     * Caller is responsible for detecting the flip; the updater unconditionally
     * increments `booksFinished`.
     */
    suspend fun onPositionFinishedFlip(userId: String) {
        val base = userStatsRepo.getForUser(userId) ?: emptyStatsFor(userId)
        userStatsRepo.upsert(base.copy(booksFinished = base.booksFinished + 1), clientOpId = null, userId = userId)
    }

    /**
     * Recompute the rolling-window fields against the current clock. Used by
     * `UserStatsRepository.pullSince`'s lazy-decay path so an idle user's
     * windows don't stay stale forever.
     */
    internal suspend fun recomputeWindowsOnly(
        userId: String,
        asOfMs: Long,
    ) {
        val existing = userStatsRepo.getForUser(userId) ?: return
        val last7 = sumWindowSeconds(userId, days = 7, asOfMs = asOfMs)
        val last30 = sumWindowSeconds(userId, days = 30, asOfMs = asOfMs)
        if (last7 != existing.totalSecondsLast7Days || last30 != existing.totalSecondsLast30Days) {
            userStatsRepo.upsert(
                existing.copy(totalSecondsLast7Days = last7, totalSecondsLast30Days = last30),
                clientOpId = null,
                userId = userId,
            )
        }
    }

    /**
     * Compute the new `currentStreakDays` value given the prior `lastEventDate`,
     * the new event's date, and the prior streak count.
     *  - `lastEventDate == null`  → 1 (first ever event)
     *  - `eventDate == lastDate`  → existing streak (same-day event, no change)
     *  - `eventDate == lastDate + 1 day` → existing + 1
     *  - otherwise (gap > 1 day OR event earlier than lastDate — backfill case)  → 1
     */
    private fun newStreakValue(
        lastEventDate: String?,
        eventDate: String,
        existingStreak: Int,
    ): Int {
        if (lastEventDate == null) return 1
        val last = LocalDate.parse(lastEventDate)
        val today = LocalDate.parse(eventDate)
        return when (today) {
            last -> existingStreak.coerceAtLeast(1)
            last.plus(DatePeriod(days = 1)) -> existingStreak + 1
            else -> 1
        }
    }

    private suspend fun hasOtherEventForBook(
        userId: String,
        bookId: String,
        excludingId: String,
    ): Boolean =
        suspendTransaction(db) {
            ListeningEventTable
                .selectAll()
                .where {
                    (ListeningEventTable.userId eq userId) and
                        (ListeningEventTable.bookId eq bookId) and
                        (ListeningEventTable.id neq excludingId)
                }.limit(1)
                .any()
        }

    private suspend fun sumWindowSeconds(
        userId: String,
        days: Int,
        asOfMs: Long,
    ): Long {
        val cutoffMs = asOfMs - days * 86_400_000L
        return suspendTransaction(db) {
            ListeningEventTable
                .selectAll()
                .where {
                    (ListeningEventTable.userId eq userId) and
                        (ListeningEventTable.endedAt greaterEq cutoffMs)
                }.toList()
                .sumOf { row ->
                    (row[ListeningEventTable.endedAt] - row[ListeningEventTable.startedAt]) / 1_000L
                }
        }
    }

    private fun emptyStatsFor(userId: String): UserStatsSyncPayload =
        UserStatsSyncPayload(
            id = userId,
            totalSecondsAllTime = 0L,
            totalSecondsLast7Days = 0L,
            totalSecondsLast30Days = 0L,
            booksStarted = 0,
            booksFinished = 0,
            currentStreakDays = 0,
            longestStreakDays = 0,
            lastEventDate = null,
            revision = 0L,
            updatedAt = 0L,
            createdAt = 0L,
            deletedAt = null,
        )
}
