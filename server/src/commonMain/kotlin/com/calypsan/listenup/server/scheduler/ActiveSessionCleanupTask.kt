package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.util.runCatchingCancellable
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = loggerFor<ActiveSessionCleanupTask>()

/**
 * Periodic sweep that hard-deletes stale `active_sessions` rows.
 *
 * Active sessions SHOULD get cleaned up by:
 *  - The cascade in `PlaybackPositionRepository.recordPosition` on book completion.
 *  - The `session.ended` sync event from the client (via the client-initiated
 *    soft-delete path on the substrate).
 *
 * But ungraceful disconnects (battery die, OS-kill, lost sync event) leave orphan
 * rows. This task catches them after the staleness threshold by deleting rows
 * whose `updated_at` column has not been refreshed within [staleAfter].
 *
 * Runs on the supplied [CoroutineScope]; the caller cancels the returned [Job]
 * when the application stops. The loop re-raises [CancellationException] so
 * structured concurrency is respected, and suppresses all other exceptions with
 * a warning log so a transient DB hiccup does not stop the sweep permanently.
 */
internal class ActiveSessionCleanupTask(
    private val sql: ListenUpDatabase,
    private val bus: ChangeBus,
    private val clock: Clock = Clock.System,
    private val interval: Duration = 5.minutes,
    private val staleAfter: Duration = 30.minutes,
) {
    /**
     * Start the sweep loop on [scope]. Returns the [Job] — cancel it to stop.
     */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                runCatchingCancellable { runOnce() }
                    .onFailure { log.warn(it) { "ActiveSessionCleanupTask sweep failed; will retry next interval" } }
                delay(interval)
            }
        }

    /**
     * Delete all rows whose `updated_at` column is older than [staleAfter] relative
     * to the current clock. Returns the number of rows deleted.
     */
    suspend fun runOnce(): Int {
        val removed =
            suspendTransaction(sql) {
                val cutoffMs = clock.now().toEpochMilliseconds() - staleAfter.inWholeMilliseconds
                sql.activeSessionsQueries.deleteStaleBefore(cutoffMs)
                sql.activeSessionsQueries
                    .changes()
                    .executeAsOne()
                    .toInt()
            }
        if (removed > 0) {
            log.info { "ActiveSessionCleanupTask removed $removed stale active_sessions rows" }
            // A sweep changes who is present — nudge connected clients to re-derive presence.
            bus.broadcastControl(SyncControl.ActiveSessionsChanged)
        }
        return removed
    }
}
