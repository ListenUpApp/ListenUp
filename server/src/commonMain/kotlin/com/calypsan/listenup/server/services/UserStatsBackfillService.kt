package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import kotlin.time.Clock

/**
 * Rebuilds the materialized `user_stats` row from scratch by replaying all
 * `listening_events` for a user, plus counting `playback_positions` where
 * `finished = true`.
 *
 * Useful when the stats schema changes, after a bug, or after restoring from
 * backup. Idempotent — running it twice produces the same result.
 *
 * **Engines.** Everything reads through [sql] (the SQLDelight [ListenUpDatabase]): the event and
 * position reads, plus the day-boundary timezone from `users` ([homeTimeZone]). The backfill runs
 * as a standalone admin/import operation (not nested inside another write transaction), so its
 * reads and the [UserStatsRepository.upsert] write are independent transactions.
 */
class UserStatsBackfillService(
    private val sql: ListenUpDatabase,
    private val userStatsRepo: UserStatsRepository,
    private val clock: Clock = Clock.System,
) {
    /**
     * Rebuilds the `user_stats` row for [userId] by re-deriving it from the raw primitives and
     * replacing any pre-existing row. A thin wrapper over the shared [deriveUserStats] — the same
     * derivation [StatsRecorder] runs per event — so a bulk rebuild and the live path always agree.
     */
    suspend fun backfillFor(userId: String) {
        val derived = deriveUserStats(sql, userId, clock.now().toEpochMilliseconds())
        userStatsRepo.upsert(derived, clientOpId = null, userId = userId)
    }
}
