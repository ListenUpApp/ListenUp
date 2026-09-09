package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = loggerFor<MetadataCacheCleanupTask>()

/**
 * Periodic sweep that hard-deletes expired rows from the metadata cache table.
 *
 * Lazy eviction in [MetadataCacheRepository.get] removes rows on read, but
 * cache keys that are never re-read accumulate indefinitely. This task is the
 * sweep-eviction counterpart: it calls [MetadataCacheRepository.deleteExpired]
 * on a schedule so orphaned rows don't fill the database.
 *
 * Runs on the supplied [CoroutineScope]; the caller cancels the returned [Job]
 * when the application stops. The loop re-raises [CancellationException] so
 * structured concurrency is respected, and suppresses all other exceptions with
 * a warning log so a transient DB hiccup does not stop the sweep permanently.
 *
 * Mirrors [com.calypsan.listenup.server.scheduler.ActiveSessionCleanupTask].
 */
internal class MetadataCacheCleanupTask(
    private val cache: MetadataCacheRepository,
    private val clock: Clock = Clock.System,
    private val interval: Duration = 1.hours,
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
                }.onFailure { log.warn(it) { "MetadataCacheCleanupTask sweep failed; will retry next interval" } }
                delay(interval)
            }
        }

    /**
     * Delete all expired rows relative to the current clock. Returns the count
     * of deleted rows. Testable without a running coroutine.
     */
    suspend fun runOnce(): Int {
        val removed = cache.deleteExpired(clock.now().toEpochMilliseconds())
        if (removed > 0) log.info { "MetadataCacheCleanupTask pruned $removed expired metadata cache rows" }
        return removed
    }

    /** What is left of [interval] since the last recorded sweep; zero when unknown or unreadable. */
    private suspend fun untilNextSweep(): Duration =
        runCatchingCancellable {
            val lastRunMs =
                settings?.getValue(LAST_RUN_KEY)?.toLongOrNull() ?: return@runCatchingCancellable Duration.ZERO
            val elapsed = (clock.now().toEpochMilliseconds() - lastRunMs).milliseconds
            (interval - elapsed).coerceIn(Duration.ZERO, interval)
        }.onFailure { log.warn(it) { "MetadataCacheCleanupTask could not read its last run; sweeping now" } }
            .getOrDefault(Duration.ZERO)

    /** Recorded only after a sweep succeeded — a failed sweep must not push the next one out. */
    private suspend fun recordSweep() {
        settings?.setValue(LAST_RUN_KEY, clock.now().toEpochMilliseconds().toString())
    }

    internal companion object {
        /** `server_settings` key holding the epoch-millis of the last successful sweep. */
        const val LAST_RUN_KEY = "scheduler.metadataCacheCleanup.lastRunAtMs"

        /** Enough to spread the boot sweeps of the cleanup tasks apart; small enough to be invisible. */
        private val START_JITTER = 10.seconds
    }
}
