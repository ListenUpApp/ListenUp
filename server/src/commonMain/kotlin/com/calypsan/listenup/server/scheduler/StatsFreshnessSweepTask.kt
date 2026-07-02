package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.services.UserStatsUpdater
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = loggerFor<StatsFreshnessSweepTask>()

/**
 * Periodic sweep that heals clock-relative stats decay for idle users.
 *
 * A user who keeps listening stays fresh via the `StatsRecorder` cascade, and a user who opens the app
 * self-heals on pull (`UserStatsRepository.pullSince`'s lazy path). But a fully-idle user — one who
 * neither listens nor syncs — keeps a frozen `current_streak_days` and stale rolling windows in their
 * `user_stats` row and, worse, in the global `public_profiles` leaderboard everyone else reads. This
 * sweep re-derives every user's stats against the current clock on a fixed [interval]; the per-user heal
 * is a cheap no-op when nothing drifted, so an all-fresh fleet costs one derive-and-compare each.
 *
 * O(users) derives per run — fine for the self-hosted small-userbase scale this app targets, not a
 * large-fleet design. Runs on the supplied [CoroutineScope]; cancel the returned [Job] to stop. Re-raises
 * `CancellationException`; suppresses other failures with a warning so a transient hiccup doesn't kill it.
 */
internal class StatsFreshnessSweepTask(
    private val sql: ListenUpDatabase,
    private val updater: UserStatsUpdater,
    private val clock: Clock = Clock.System,
    private val interval: Duration = 6.hours,
) {
    /** Start the sweep loop on [scope]. Returns the [Job] — cancel it to stop. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                runCatchingCancellable { runOnce() }
                    .onFailure { log.warn(it) { "StatsFreshnessSweepTask sweep failed; will retry next interval" } }
                delay(interval)
            }
        }

    /** Heal every live user's stats against the current clock. Returns the number of rows healed. */
    suspend fun runOnce(): Int {
        val userIds =
            suspendTransaction(sql) { sql.userStatsQueries.selectAllLiveUserIds().executeAsList() }
        val nowMs = clock.now().toEpochMilliseconds()
        var healed = 0
        for (userId in userIds) {
            if (updater.healStaleStats(userId, asOfMs = nowMs)) healed++
        }
        if (healed > 0) log.info { "StatsFreshnessSweepTask healed $healed stale user_stats rows" }
        return healed
    }
}
