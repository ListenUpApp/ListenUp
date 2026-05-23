package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.db.ActiveSessionTable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val log = KotlinLogging.logger {}

/**
 * Periodic sweep that hard-deletes stale `active_sessions` rows.
 *
 * Active sessions SHOULD get cleaned up by:
 *  - The cascade in `PlaybackPositionRepository.recordPosition` on book completion.
 *  - The `session.ended` SSE event from the client (via the client-initiated
 *    soft-delete path on the substrate).
 *
 * But ungraceful disconnects (battery die, OS-kill, lost SSE event) leave orphan
 * rows. This task catches them after the staleness threshold by deleting rows
 * whose `updated_at` column has not been refreshed within [staleAfter].
 *
 * Runs on the supplied [CoroutineScope]; the caller cancels the returned [Job]
 * when the application stops. The loop re-raises [CancellationException] so
 * structured concurrency is respected, and suppresses all other exceptions with
 * a warning log so a transient DB hiccup does not stop the sweep permanently.
 */
internal class ActiveSessionCleanupTask(
    private val db: Database,
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
                try {
                    runOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "ActiveSessionCleanupTask sweep failed; will retry next interval" }
                }
                delay(interval)
            }
        }

    /**
     * Delete all rows whose `updated_at` column is older than [staleAfter] relative
     * to the current clock. Returns the number of rows deleted.
     */
    suspend fun runOnce(): Int =
        suspendTransaction(db) {
            val cutoffMs = clock.now().toEpochMilliseconds() - staleAfter.inWholeMilliseconds
            val removed =
                ActiveSessionTable.deleteWhere {
                    ActiveSessionTable.updatedAt less cutoffMs
                }
            if (removed > 0) log.info { "ActiveSessionCleanupTask removed $removed stale active_sessions rows" }
            removed
        }
}
