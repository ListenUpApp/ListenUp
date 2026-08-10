@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.util.runCatchingCancellable
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = loggerFor<ExpiredPasswordResetCleanupTask>()

/**
 * Periodic sweep that hard-deletes `password_reset_requests` rows whose `expires_at` is more
 * than [retention] in the past.
 *
 * Reaps long-dead reset rows. **Hygiene, not correctness** — expiry is enforced in the WHERE
 * clause of every read ([com.calypsan.listenup.server.services.PasswordResetService] never
 * trusts a persisted status without also checking `expires_at` against the clock), so a purge
 * that never runs cannot resurrect a request. This only stops the table growing without bound.
 *
 * Runs on the supplied [CoroutineScope]; the caller cancels the returned [Job] when the
 * application stops. The loop re-raises [kotlinx.coroutines.CancellationException] so structured
 * concurrency is respected, and suppresses all other exceptions with a warning log so a
 * transient DB hiccup does not stop the sweep permanently.
 *
 * Mirrors [com.calypsan.listenup.server.scheduler.ExpiredSessionCleanupTask].
 */
internal class ExpiredPasswordResetCleanupTask(
    private val db: ListenUpDatabase,
    private val clock: Clock = Clock.System,
    private val interval: Duration = 1.hours,
    private val retention: Duration = 1.days,
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
                    .onFailure {
                        log.warn(it) { "ExpiredPasswordResetCleanupTask sweep failed; will retry next interval" }
                    }
            }
        }

    /**
     * Delete every `password_reset_requests` row whose `expires_at` is more than [retention] in
     * the past. Returns the count of deleted rows. Testable without a running coroutine.
     */
    suspend fun runOnce(): Int {
        val cutoff = (clock.now() - retention).toEpochMilliseconds()
        val removed =
            suspendTransaction(db) {
                db.passwordResetRequestsQueries.deleteExpiredBefore(cutoff)
                db.passwordResetRequestsQueries
                    .changes()
                    .executeAsOne()
                    .toInt()
            }
        if (removed > 0) log.info { "ExpiredPasswordResetCleanupTask pruned $removed expired password-reset rows" }
        return removed
    }
}
