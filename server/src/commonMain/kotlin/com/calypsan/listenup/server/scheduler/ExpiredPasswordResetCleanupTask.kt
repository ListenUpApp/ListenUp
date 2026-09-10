@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
    /** Nullable — without it the last run is not persisted and every boot sweeps at once. */
    private val settings: ServerSettingsRepository? = null,
    /** Upper bound on the random delay before the first sweep; zero in tests. */
    private val startJitter: Duration = START_JITTER,
) {
    /**
     * Start the sweep loop on [scope]. Returns the [Job] — cancel it to stop.
     *
     * Sweeps at boot — after a short random delay up to [startJitter], so tasks restarted together
     * do not hit the DB in the same instant — unless the persisted last run says one happened
     * within [interval], in which case it waits out the remainder. A nightly-restarted server thus
     * sweeps once per [interval], never once per restart and never never.
     */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            delay(Random.nextLong(startJitter.inWholeMilliseconds + 1))
            delay(untilNextSweep())
            while (isActive) {
                runCatchingCancellable {
                    runOnce()
                    recordSweep()
                }.onFailure {
                    log.warn(it) { "ExpiredPasswordResetCleanupTask sweep failed; will retry next interval" }
                }
                delay(interval)
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

    /** What is left of [interval] since the last recorded sweep; zero when unknown or unreadable. */
    private suspend fun untilNextSweep(): Duration =
        runCatchingCancellable {
            val lastRunMs =
                settings?.getValue(LAST_RUN_KEY)?.toLongOrNull() ?: return@runCatchingCancellable Duration.ZERO
            val elapsed = (clock.now().toEpochMilliseconds() - lastRunMs).milliseconds
            (interval - elapsed).coerceIn(Duration.ZERO, interval)
        }.onFailure { log.warn(it) { "ExpiredPasswordResetCleanupTask could not read its last run; sweeping now" } }
            .getOrDefault(Duration.ZERO)

    /** Recorded only after a sweep succeeded — a failed sweep must not push the next one out. */
    private suspend fun recordSweep() {
        settings?.setValue(LAST_RUN_KEY, clock.now().toEpochMilliseconds().toString())
    }

    internal companion object {
        /** `server_settings` key holding the epoch-millis of the last successful sweep. */
        const val LAST_RUN_KEY = "scheduler.expiredPasswordResetCleanup.lastRunAtMs"

        /** Enough to spread the boot sweeps of the cleanup tasks apart; small enough to be invisible. */
        private val START_JITTER = 10.seconds
    }
}
