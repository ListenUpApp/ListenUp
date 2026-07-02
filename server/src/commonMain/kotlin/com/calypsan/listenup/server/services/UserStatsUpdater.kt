package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase

/**
 * Stale-stats self-heal for the two clock-relative reads that drift on inactivity: the rolling
 * 7/30-day window totals AND the current streak (a lapsed user's streak must fall to 0 once their
 * last listening day is older than yesterday). Both are pure functions of the committed primitives, so
 * healing is a full [deriveUserStats] re-run against the current clock — the same derivation the event
 * cascade and the bulk rebuild use, so a healed row can't diverge from a freshly-written one.
 *
 * Two callers drive it: [UserStatsRepository.pullSince]'s lazy path (heal a stale row before returning
 * it to a syncing client) and the periodic [com.calypsan.listenup.server.scheduler.StatsFreshnessSweepTask]
 * (heal idle users who never pull). When a heal changes the row, the `public_profiles` projection is
 * refreshed too (best-effort) so the leaderboard everyone else reads decays with it.
 *
 * Allowlisted alongside [StatsRecorder] and [UserStatsBackfillService] in
 * [com.calypsan.listenup.server.konsist.StatsRecorderIsSoleStatsWriterRule]: an idempotent re-derive of
 * already-committed primitives, not a new ordering-sensitive write path.
 *
 * [publicProfileMaintainer] is optional so unit tests can exercise the `user_stats` heal without wiring
 * the projection; production (DI) always supplies it.
 */
class UserStatsUpdater(
    private val sql: ListenUpDatabase,
    private val userStatsRepo: UserStatsRepository,
    private val publicProfileMaintainer: PublicProfileMaintainer? = null,
) {
    /**
     * Re-derive [userId]'s stats as of [asOfMs] and, if the decay-sensitive fields (rolling windows or
     * current streak) changed, write the row and refresh the projection. A no-op — returning `false`
     * without writing — when the user has no stats row or nothing drifted, so it's cheap to call on
     * every pull and on every sweep tick.
     *
     * @return `true` if the row was healed (and the projection refreshed), `false` otherwise.
     */
    internal suspend fun healStaleStats(
        userId: String,
        asOfMs: Long,
    ): Boolean {
        val existing = userStatsRepo.getForUser(userId) ?: return false
        val derived = deriveUserStats(sql, userId, asOfMs)
        val drifted =
            derived.totalSecondsLast7Days != existing.totalSecondsLast7Days ||
                derived.totalSecondsLast30Days != existing.totalSecondsLast30Days ||
                derived.currentStreakDays != existing.currentStreakDays
        if (!drifted) return false
        userStatsRepo.upsert(derived, clientOpId = null, userId = userId)
        publicProfileMaintainer?.refreshBestEffort(userId)
        return true
    }
}
