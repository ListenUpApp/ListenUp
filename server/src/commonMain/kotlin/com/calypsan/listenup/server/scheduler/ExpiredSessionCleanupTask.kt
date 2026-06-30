package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.util.runCatchingCancellable
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = loggerFor<ExpiredSessionCleanupTask>()

/**
 * Periodic sweep that hard-deletes session rows whose `expires_at` timestamp is
 * in the past.
 *
 * Sessions are soft-revoked via [SessionService.revoke] / [SessionService.revokeAll]
 * when the user signs out, but rows accumulate when the session simply expires without
 * an explicit revoke (e.g. 30-day TTL elapsed with no refresh). This task is the
 * eviction counterpart: it calls [SessionService.deleteExpired] on a schedule so
 * stale rows don't fill the `sessions` table.
 *
 * Runs on the supplied [CoroutineScope]; the caller cancels the returned [Job]
 * when the application stops. The loop re-raises [CancellationException] so
 * structured concurrency is respected, and suppresses all other exceptions with
 * a warning log so a transient DB hiccup does not stop the sweep permanently.
 *
 * Mirrors [com.calypsan.listenup.server.scheduler.MetadataCacheCleanupTask].
 */
internal class ExpiredSessionCleanupTask(
    private val sessionService: SessionService,
    private val clock: Clock = Clock.System,
    private val interval: Duration = 1.hours,
) {
    /**
     * Start the sweep loop on [scope]. Returns the [Job] — cancel it to stop.
     * The first sweep runs after [interval], not immediately.
     */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                delay(interval)
                runCatchingCancellable { runOnce() }
                    .onFailure { log.warn(it) { "ExpiredSessionCleanupTask sweep failed; will retry next interval" } }
            }
        }

    /**
     * Delete all session rows whose `expires_at` is before now. Returns the count
     * of deleted rows. Testable without a running coroutine.
     */
    suspend fun runOnce(): Int {
        val removed = sessionService.deleteExpired(clock.now().toEpochMilliseconds())
        if (removed > 0) log.info { "ExpiredSessionCleanupTask pruned $removed expired session rows" }
        return removed
    }
}
