package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.util.KeyedMutex
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext

/**
 * The single server-side write choke-point for every stats-affecting trigger. [record] runs ONE
 * fixed ordering per [StatsEvent]: durable source rows (`listening_events` / `book_reads`) →
 * `user_stats` → the `public_profiles` projection (always LAST) → activity emission. Because
 * [PublicProfileMaintainer.refresh] always runs after the source rows for the same event, the
 * "all-time works, windowed fails" undercount class is impossible by construction.
 *
 * Approach B: sequential, de-nested, idempotent `suspendTransaction` steps — not one atomic
 * transaction. SQLDelight's `transactionWithResult` lambda is non-suspend, so a suspend hook call
 * cannot nest inside it (the same constraint [PlaybackPositionRepository] and
 * [ListeningEventRepository] already document). [PublicProfileMaintainer.refresh] is an idempotent
 * full re-derive, so a crash mid-cascade self-heals on the next trigger.
 *
 * During a bulk import, callers write source rows through this SAME recorder under the
 * [StatsCascadeDeferred] coroutine-context marker, which suppresses the per-row `user_stats` upsert
 * and [PublicProfileMaintainer.refresh] — the importer ends with one [StatsEvent.BulkRecompute] per
 * affected user instead.
 *
 * `UserStatsRepository.upsert` and `BookReadsRepository.recordRead` are restricted to this class
 * (plus [UserStatsBackfillService], which this class calls for [StatsEvent.BulkRecompute], and
 * [UserStatsUpdater], whose surviving lazy window-decay self-heal is a separate idempotent
 * re-derive) by [com.calypsan.listenup.server.konsist.StatsRecorderIsSoleStatsWriterRule].
 */
class StatsRecorder(
    private val sql: ListenUpDatabase,
    private val userStatsRepo: UserStatsRepository,
    private val bookReadsRepository: BookReadsRepository,
    private val publicProfileMaintainer: PublicProfileMaintainer,
    private val activityRecorder: ActivityRecorder,
    private val statsBackfill: UserStatsBackfillService,
    private val clock: Clock = Clock.System,
) {
    /**
     * Serializes [record] per user id: without this, two concurrent cascades for the same user
     * (two devices closing sessions, or a retried outbox op racing a live one) could both read the
     * same base `user_stats` row and both emit the same milestone crossing. See the class KDoc for
     * the read-base → re-derive → upsert → emit sequence this protects.
     */
    private val userLock = KeyedMutex()

    /** Routes [event] through its fixed ordering. See the class KDoc for the contract. */
    suspend fun record(event: StatsEvent) {
        userLock.withLock(event.userId) {
            when (event) {
                is StatsEvent.BookCompleted -> recordBookCompleted(event)
                is StatsEvent.BookRestarted -> recordBookRestarted(event)
                is StatsEvent.ListeningSessionClosed -> recordListeningSessionClosed(event)
                is StatsEvent.BulkRecompute -> recordBulkRecompute(event)
            }
        }
    }

    /**
     * `book_reads` (via the coverage rule — append a new read or merge a below-threshold replay) →
     * re-derived `user_stats` (booksFinished counted from `book_reads`) → `public_profiles.refresh()` →
     * the `FINISHED_BOOK` activity, dated [StatsEvent.BookCompleted.occurredAt]. The re-derive and the
     * projection refresh are skipped under [StatsCascadeDeferred] — a bulk import writes the source row
     * per row but defers the recompute to one terminal [StatsEvent.BulkRecompute].
     */
    private suspend fun recordBookCompleted(event: StatsEvent.BookCompleted) {
        val finishedAtMs = event.occurredAt.toEpochMilliseconds()
        // The coverage rule decides append-vs-merge on `book_reads`; the re-derive then reads the new
        // count. booksFinished is a pure function of `book_reads`, so a merge leaves it unchanged.
        bookReadsRepository.recordCompletion(event.userId, event.bookId, finishedAtMs)
        if (currentCoroutineContext()[StatsCascadeDeferred.Key] == null) {
            val tz = sql.homeTimeZone(event.userId)
            val base = userStatsRepo.getForUser(event.userId) ?: emptyStatsFor(event.userId)
            val derived = deriveUserStats(sql, event.userId, clock.now().toEpochMilliseconds(), tz)
            userStatsRepo.upsert(derived, clientOpId = null, userId = event.userId)
            publicProfileMaintainer.refresh(event.userId, tz)
            emitMilestoneCrossings(event.userId, base, derived)
        }
        activityRecorder.record(
            event.userId,
            ActivityType.FINISHED_BOOK,
            bookId = event.bookId,
            occurredAt = finishedAtMs,
        )
    }

    /**
     * No source row (the caller already wrote `playback_positions`), no `user_stats`/projection
     * impact (a start moves no windowed stat) — just the `STARTED_BOOK` activity, dated
     * [StatsEvent.BookRestarted.occurredAt].
     */
    private suspend fun recordBookRestarted(event: StatsEvent.BookRestarted) {
        activityRecorder.record(
            event.userId,
            ActivityType.STARTED_BOOK,
            bookId = event.bookId,
            isReread = event.isReread,
            occurredAt = event.occurredAt.toEpochMilliseconds(),
        )
    }

    /**
     * Full `user_stats` rebuild from raw rows, then a `public_profiles` refresh — the terminal step
     * a bulk import or admin backfill calls once per affected user, never once per row.
     */
    private suspend fun recordBulkRecompute(event: StatsEvent.BulkRecompute) {
        statsBackfill.backfillFor(event.userId)
        publicProfileMaintainer.refresh(event.userId)
    }

    /**
     * Re-derive `user_stats` from the primitives via [deriveUserStats] → `public_profiles.refresh()` →
     * milestone activity → the `LISTENING_SESSION` activity. The event row is already committed by the
     * caller, so the re-derive reflects it. Because every field is recomputed from all committed events
     * (not incremented), the path is crash-healing and order-independent by construction — no
     * late-arrival guard is needed. The re-derive, refresh, and milestone activity are skipped under
     * [StatsCascadeDeferred] (a bulk import defers them to one terminal [StatsEvent.BulkRecompute]); the
     * `LISTENING_SESSION` row still fires, historically dated.
     */
    private suspend fun recordListeningSessionClosed(event: StatsEvent.ListeningSessionClosed) {
        val userId = event.userId
        val span = event.span
        if (currentCoroutineContext()[StatsCascadeDeferred.Key] == null) {
            val tz = sql.homeTimeZone(userId)
            val base = userStatsRepo.getForUser(userId) ?: emptyStatsFor(userId)
            val derived = deriveUserStats(sql, userId, clock.now().toEpochMilliseconds(), tz)
            userStatsRepo.upsert(derived, clientOpId = null, userId = userId)
            publicProfileMaintainer.refresh(userId, tz)
            // Milestones fire once per forward crossing; the per-user lock in [record] makes
            // base→derived windows non-overlapping.
            emitMilestoneCrossings(userId, base, derived)
        }
        activityRecorder.record(
            userId,
            ActivityType.LISTENING_SESSION,
            bookId = span.bookId,
            durationMs = span.endedAt - span.startedAt,
            occurredAt = span.endedAt,
        )
    }

    /**
     * Emits STREAK_MILESTONE / LISTENING_MILESTONE activities for every milestone value crossed
     * forward between [base] and [derived]. Enumerates the whole (base, derived] range so a jump
     * over several thresholds emits each one; a decrease (a delete/heal re-derive lowering a value)
     * emits nothing. Both milestone lists are small constants, so the filter is bounded.
     */
    private suspend fun emitMilestoneCrossings(
        userId: String,
        base: UserStatsSyncPayload,
        derived: UserStatsSyncPayload,
    ) {
        STREAK_MILESTONES
            .filter { it > base.currentStreakDays && it <= derived.currentStreakDays }
            .forEach { milestone ->
                activityRecorder.record(
                    userId,
                    ActivityType.STREAK_MILESTONE,
                    milestoneValue = milestone,
                    milestoneUnit = "days",
                )
            }
        val prevHours = (base.totalSecondsAllTime / 3600L).toInt()
        val newHours = (derived.totalSecondsAllTime / 3600L).toInt()
        LISTENING_MILESTONES
            .filter { prevHours < it && newHours >= it }
            .forEach { milestone ->
                activityRecorder.record(
                    userId,
                    ActivityType.LISTENING_MILESTONE,
                    milestoneValue = milestone,
                    milestoneUnit = "hours",
                )
            }
    }

    private companion object {
        private val STREAK_MILESTONES = listOf(7, 14, 30, 60, 100, 365)
        private val LISTENING_MILESTONES = listOf(10, 50, 100, 250, 500, 1000)
    }

    /** A zero-valued `user_stats` payload for a user with no prior row — moved from [UserStatsUpdater]. */
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
