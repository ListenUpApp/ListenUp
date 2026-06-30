package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase

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
) {
    /** Routes [event] through its fixed ordering. See the class KDoc for the contract. */
    suspend fun record(event: StatsEvent) {
        when (event) {
            is StatsEvent.BookCompleted -> Unit // wired in Task 2
            is StatsEvent.ListeningSessionClosed -> Unit // wired in Task 4
            is StatsEvent.BookRestarted -> Unit // wired in Task 5
            is StatsEvent.BulkRecompute -> Unit // wired in Task 6
        }
    }
}
