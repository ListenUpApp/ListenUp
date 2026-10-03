package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.activity.RealListen
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.SelectAwaitingRealStart
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.hardcover.HardcoverPushHook
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.util.KeyedMutex
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.datetime.TimeZone

private val log = loggerFor<StatsRecorder>()

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
    private val hardcoverPush: HardcoverPushHook = HardcoverPushHook.None,
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
        val appended = bookReadsRepository.recordCompletion(event.userId, event.bookId, finishedAtMs)
        closeAwaitingListenThrough(event.userId, event.bookId, finishedAtMs)
        // Only a genuinely new read reaches Hardcover: a merged replay is the same read, already pushed.
        if (appended) {
            pushToHardcover(
                event.userId,
                "finish",
            ) { onReadAppended(event.userId, event.bookId, finishedAtMs) }
        }
        if (currentCoroutineContext()[StatsCascadeDeferred.Key] == null) {
            val tz = sql.homeTimeZone(event.userId)
            val base = userStatsRepo.getForUser(event.userId) ?: emptyStatsFor(event.userId)
            val derived = deriveUserStats(sql, event.userId, clock.now().toEpochMilliseconds(), tz)
            userStatsRepo.upsert(derived, clientOpId = null, userId = event.userId)
            publicProfileMaintainer.refresh(event.userId, tz)
            emitMilestoneCrossings(event.userId, base, derived, tz)
        }
        activityRecorder.record(
            event.userId,
            ActivityType.FINISHED_BOOK,
            bookId = event.bookId,
            occurredAt = finishedAtMs,
        )
    }

    /**
     * Begins a new listen-through — the first position on a book, or a finished book reopened. No
     * source row beyond the listen-through marker, and no `user_stats`/projection impact (a start
     * moves no windowed stat). The `STARTED_BOOK` activity is NOT raised here: an eight-second tap is
     * not news. [announceRealStartIfCrossed] raises it once the listen-through holds a real listen —
     * checked here too, because an import can write a book's sessions before its restart.
     *
     * Best-effort ([runCatchingCancellable]): this bookkeeping is server-internal and must never fail
     * the position/session write that triggered it — a book id whose `books` row has since vanished
     * (the `listen_throughs` FK) would otherwise abort an already-committed RPC call, and inside
     * [PlaybackPositionRepository.recordAllForImport]'s per-row hook loop, would abort every row after
     * it. A failure here costs one listen-through's bookkeeping, never the caller.
     */
    private suspend fun recordBookRestarted(event: StatsEvent.BookRestarted) {
        runCatchingCancellable {
            val startedAtMs = event.occurredAt.toEpochMilliseconds()
            val isRereadFlag = if (event.isReread) 1L else 0L
            suspendTransaction(sql) {
                // Seeds the row on a genuine first restart (a no-op if one already exists), then
                // resets an existing row only when this restart is genuinely new (see ListenThroughs.sq).
                sql.listenThroughsQueries.beginListenThrough(
                    user_id = event.userId,
                    book_id = event.bookId,
                    started_at = startedAtMs,
                    is_reread = isRereadFlag,
                )
                sql.listenThroughsQueries.resetListenThroughIfChanged(
                    started_at = startedAtMs,
                    is_reread = isRereadFlag,
                    user_id = event.userId,
                    book_id = event.bookId,
                )
            }
            announceRealStartIfCrossed(event.userId, event.bookId)
        }.onFailure {
            log.warn(it) { "listen-through bookkeeping failed on restart user=${event.userId} book=${event.bookId}" }
        }
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
            emitMilestoneCrossings(userId, base, derived, tz)
        }
        activityRecorder.record(
            userId,
            ActivityType.LISTENING_SESSION,
            bookId = span.bookId,
            durationMs = span.endedAt - span.startedAt,
            occurredAt = span.endedAt,
        )
        // Best-effort — see recordBookRestarted's KDoc: this must never fail an already-committed
        // listening-event write.
        runCatchingCancellable { announceRealStartIfCrossed(userId, span.bookId) }
            .onFailure {
                log.warn(
                    it,
                ) { "listen-through bookkeeping failed on session-close user=$userId book=${span.bookId}" }
            }
        // After the real-start check, so a session that crosses the line queues START before its PROGRESS.
        pushToHardcover(userId, "progress") { onSessionClosed(userId, span.bookId, span.endPositionMs) }
    }

    /**
     * Raises `STARTED_BOOK` the first time the user's current listen-through of [bookId] holds a real
     * listen ([RealListen.THRESHOLD_MS] of wall-clock listening since it began), dated at the
     * listen-through's start — when they actually started, not when the minute ran out.
     *
     * Announces at most once per listen-through: `markRealStarted` only updates a row still awaiting
     * its real start, and the announcement fires only when it changed one. A book with no
     * listen-through row (already in progress before listen-throughs existed) never announces again.
     */
    private suspend fun announceRealStartIfCrossed(
        userId: String,
        bookId: String,
    ) {
        val crossed =
            suspendTransaction<SelectAwaitingRealStart?>(sql) { claimRealStartIfCrossed(userId, bookId) } ?: return
        activityRecorder.record(
            userId,
            ActivityType.STARTED_BOOK,
            bookId = bookId,
            isReread = crossed.is_reread == 1L,
            occurredAt = crossed.started_at,
        )
        pushToHardcover(userId, "start") {
            onRealStart(userId, bookId, startedAt = crossed.started_at, isReread = crossed.is_reread == 1L)
        }
    }

    /**
     * Hands one event to Hardcover sync. Skipped under [StatsCascadeDeferred]: an import replays history,
     * and history is never pushed. Best-effort — queueing a push must never fail the stats write that
     * triggered it; the outbox is the retry mechanism for Hardcover itself, not for this bookkeeping.
     */
    private suspend fun pushToHardcover(
        userId: String,
        what: String,
        block: suspend HardcoverPushHook.() -> Unit,
    ) {
        if (currentCoroutineContext()[StatsCascadeDeferred.Key] != null) return
        runCatchingCancellable { hardcoverPush.block() }
            .onFailure { log.warn(it) { "hardcover $what enqueue failed user=$userId" } }
    }

    /**
     * A finished listen-through is over: closes it without announcing, so a book finished from a tap
     * that never crossed [RealListen.THRESHOLD_MS] can't grow a `STARTED_BOOK` AFTER its
     * `FINISHED_BOOK` when spans belonging to it are written later (a delayed sync, an import). A
     * re-read begins its own new listen-through via [recordBookRestarted], not this.
     *
     * Best-effort — see [recordBookRestarted]'s KDoc.
     */
    private suspend fun closeAwaitingListenThrough(
        userId: String,
        bookId: String,
        closedAtMs: Long,
    ) {
        runCatchingCancellable {
            suspendTransaction(sql) {
                sql.listenThroughsQueries.closeAwaitingListenThrough(
                    closed_at = closedAtMs,
                    user_id = userId,
                    book_id = bookId,
                )
            }
        }.onFailure {
            log.warn(it) { "listen-through bookkeeping failed on completion user=$userId book=$bookId" }
        }
    }

    /**
     * The non-suspend body of [announceRealStartIfCrossed]'s transaction: returns the listen-through
     * row that just crossed [RealListen.THRESHOLD_MS], or `null` if it hasn't (or there is none awaiting
     * it). A plain function rather than a lambda with a labelled `return@suspendTransaction` — not
     * because the label doesn't type-check (it does), but because a plain function keeps the early
     * returns local and readable without depending on `suspendTransaction`'s type parameter being
     * pinned by the call's context.
     */
    private fun claimRealStartIfCrossed(
        userId: String,
        bookId: String,
    ): SelectAwaitingRealStart? {
        val awaiting =
            sql.listenThroughsQueries.selectAwaitingRealStart(user_id = userId, book_id = bookId).executeAsOneOrNull()
                ?: return null
        val listenedMs =
            sql.listeningEventsQueries
                .sumWallMsForBookEndedAfter(userId = userId, bookId = bookId, sinceMs = awaiting.started_at)
                .executeAsOne()
        if (listenedMs < RealListen.THRESHOLD_MS) return null
        val claimed =
            sql.listenThroughsQueries
                .markRealStarted(
                    real_started_at = clock.now().toEpochMilliseconds(),
                    user_id = userId,
                    book_id = bookId,
                ).value
        return if (claimed == 1L) awaiting else null
    }

    /**
     * Emits STREAK_MILESTONE / LISTENING_MILESTONE activities for every milestone value crossed
     * forward between [base] and [derived]. Enumerates the whole (base, derived] range so a jump
     * over several thresholds emits each one; a decrease (a delete/heal re-derive lowering a value)
     * emits nothing. Both milestone lists are small constants, so the filter is bounded.
     *
     * A streak crossing is only a candidate: [emitStreakMilestones] announces it once per streak run.
     */
    private suspend fun emitMilestoneCrossings(
        userId: String,
        base: UserStatsSyncPayload,
        derived: UserStatsSyncPayload,
        tz: TimeZone,
    ) {
        val streakCrossings =
            STREAK_MILESTONES.filter { it > base.currentStreakDays && it <= derived.currentStreakDays }
        if (streakCrossings.isNotEmpty()) emitStreakMilestones(userId, streakCrossings, derived, tz)
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

    /**
     * Announces each crossed streak milestone at most once per streak run, dated when it was earned.
     *
     * The stored base streak is not a record of what was announced: the decay heal drops it to 0 the
     * morning after a day with no *synced* listening, and when that day's listening arrives late the
     * re-derive jumps straight back — re-crossing every milestone the run already announced. So the
     * run itself is the key: a milestone already on record inside the current run (dated at or after
     * its first day) is not announced again, while a real gap begins a new run that earns them afresh.
     *
     * Each new milestone is dated at the first listening on the run's threshold day — when it was
     * actually earned — not at the moment the server heard about it.
     */
    private suspend fun emitStreakMilestones(
        userId: String,
        crossed: List<Int>,
        derived: UserStatsSyncPayload,
        tz: TimeZone,
    ) {
        val run = currentStreakRun(sql, userId, derived.currentStreakDays, tz) ?: return
        crossed.forEach { milestone ->
            val alreadyAnnounced =
                suspendTransaction(sql) {
                    sql.activitiesQueries
                        .hasStreakMilestoneInRun(
                            user_id = userId,
                            milestone_value = milestone.toLong(),
                            run_started_at = run.startMs,
                        ).executeAsOne()
                }
            if (!alreadyAnnounced) {
                activityRecorder.record(
                    userId,
                    ActivityType.STREAK_MILESTONE,
                    milestoneValue = milestone,
                    milestoneUnit = "days",
                    occurredAt = run.reachedLengthAtMs(milestone),
                )
            }
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
