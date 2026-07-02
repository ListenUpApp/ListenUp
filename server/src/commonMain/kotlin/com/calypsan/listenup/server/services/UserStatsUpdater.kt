package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/**
 * Lazy window-decay self-heal for [UserStatsRepository.pullSince]: recomputes the rolling 7/30-day
 * window fields against the current clock when a user's materialized `user_stats` row has gone
 * stale from inactivity (an event from 6 days ago is inside the 7-day window today but outside it
 * in 2 days). This is its only remaining job — the event-driven write cascade (all-time counters,
 * streaks, milestones, the `book_reads` / `public_profiles` writes, and activity emission) now lives
 * in [StatsRecorder], the single choke-point for every stats-affecting write ([StatsEvent]).
 *
 * Allowlisted alongside [StatsRecorder] and [UserStatsBackfillService] in
 * [com.calypsan.listenup.server.konsist.StatsRecorderIsSoleStatsWriterRule]: this recompute is an
 * idempotent re-derive of already-committed `listening_events`, the same character as
 * [UserStatsBackfillService.backfillFor] — not a new write path that could under-deliver an
 * ordering guarantee.
 */
class UserStatsUpdater(
    private val sql: ListenUpDatabase,
    private val userStatsRepo: UserStatsRepository,
) {
    /**
     * Recompute the rolling-window fields against [asOfMs]. Used by
     * [UserStatsRepository.pullSince]'s lazy-decay path so an idle user's windows don't stay stale
     * forever.
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

    private suspend fun sumWindowSeconds(
        userId: String,
        days: Int,
        asOfMs: Long,
    ): Long {
        val cutoffMs = asOfMs - days * 86_400_000L
        return suspendTransaction(sql) {
            sql.listeningEventsQueries.sumWallSecondsSince(userId = userId, cutoffMs = cutoffMs).executeAsOne()
        }
    }
}
