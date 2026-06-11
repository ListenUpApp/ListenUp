package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

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
                    .onFailure { log.warn(it) { "MetadataCacheCleanupTask sweep failed; will retry next interval" } }
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
}
