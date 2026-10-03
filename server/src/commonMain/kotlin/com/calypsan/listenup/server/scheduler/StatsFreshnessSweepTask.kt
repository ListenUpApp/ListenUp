package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.services.UserStatsUpdater
import com.calypsan.listenup.server.services.homeTimeZone
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime

private val log = loggerFor<StatsFreshnessSweepTask>()

/**
 * Rolls every listener's stats over at their own local midnight.
 *
 * Every clock-relative stat — the calendar week/month/year windows
 * ([com.calypsan.listenup.domain.stats.StatsWindow]) and the current streak — only moves when the
 * listener's local date changes. A user who keeps listening stays fresh via the `StatsRecorder`
 * cascade, and one who opens the app self-heals on pull (`UserStatsRepository.pullSince`'s lazy
 * path). A fully idle user does neither, so without this sweep their `user_stats` row — and the global
 * `public_profiles` leaderboard everyone else reads — would keep last week's numbers.
 *
 * Each tick heals the users whose local date (in their home timezone) has changed since the previous
 * completed tick; the first tick after startup heals everyone. Ticking every [interval] bounds how long
 * after a listener's midnight their week can stay stale, whatever their UTC offset. Healing is an
 * idempotent derive-and-compare that only writes rows that actually moved, so a repeated tick is a
 * no-op. A failed tick doesn't advance the mark, so the next tick retries the same users.
 *
 * O(users) timezone reads per tick, plus one derive per user crossing midnight — fine for the
 * self-hosted small-userbase scale this app targets. Runs on the supplied [CoroutineScope]; cancel the
 * returned [Job] to stop. Re-raises `CancellationException`; suppresses other failures with a warning
 * so a transient hiccup doesn't kill it.
 */
internal class StatsFreshnessSweepTask(
    private val sql: ListenUpDatabase,
    private val updater: UserStatsUpdater,
    private val clock: Clock = Clock.System,
    internal val interval: Duration = 15.minutes,
) {
    /** The moment the last tick completed; `null` until the first one does. */
    private var lastSweptAt: Instant? = null

    /** Start the sweep loop on [scope]. Returns the [Job] — cancel it to stop. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                runCatchingCancellable { runOnce() }
                    .onFailure { log.warn(it) { "StatsFreshnessSweepTask sweep failed; will retry next interval" } }
                delay(interval)
            }
        }

    /**
     * Heal every live user whose local date has changed since the previous completed tick (everyone,
     * on the first). Returns the number of users whose stats or leaderboard row was healed.
     */
    suspend fun runOnce(): Int {
        val userIds =
            suspendTransaction(sql) { sql.userStatsQueries.selectAllLiveUserIds().executeAsList() }
        val now = clock.now()
        val previous = lastSweptAt
        var healed = 0
        for (userId in userIds) {
            if (previous != null && !crossedLocalMidnight(userId, previous, now)) continue
            if (updater.healStaleStats(userId, asOfMs = now.toEpochMilliseconds())) healed++
        }
        lastSweptAt = now
        if (healed > 0) log.info { "StatsFreshnessSweepTask rolled over $healed users' stats at local midnight" }
        return healed
    }

    private suspend fun crossedLocalMidnight(
        userId: String,
        from: Instant,
        to: Instant,
    ): Boolean {
        val zone = sql.homeTimeZone(userId)
        return from.toLocalDateTime(zone).date != to.toLocalDateTime(zone).date
    }
}
