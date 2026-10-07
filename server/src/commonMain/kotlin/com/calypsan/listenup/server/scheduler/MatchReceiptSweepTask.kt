package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.matching.undo.MatchReceiptStore
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = loggerFor<MatchReceiptSweepTask>()

/**
 * Daily sweep of match receipts that can no longer be undone — undone ones, and ones whose book has changed or
 * gone since (spec, *Undo*, retention). Deleting a receipt releases the old cover file it kept for Undo, which
 * the orphan-image sweep then reaps as usual. Same loop shape as [MetadataCacheCleanupTask].
 */
internal class MatchReceiptSweepTask(
    private val receipts: MatchReceiptStore,
    private val clock: Clock = Clock.System,
    private val interval: Duration = 1.days,
    /** Nullable — without it the last run is not persisted and every boot sweeps at once. */
    private val settings: ServerSettingsRepository? = null,
    /** Upper bound on the random delay before the first sweep; zero in tests. */
    private val startJitter: Duration = START_JITTER,
) {
    /** Start the sweep loop on [scope]. Returns the [Job] — cancel it to stop. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            delay(Random.nextLong(startJitter.inWholeMilliseconds + 1))
            delay(untilNextSweep())
            while (isActive) {
                runCatchingCancellable {
                    runOnce()
                    recordSweep()
                }.onFailure { log.warn(it) { "MatchReceiptSweepTask sweep failed; will retry next interval" } }
                delay(interval)
            }
        }

    /** Deletes every receipt that can no longer be undone. */
    suspend fun runOnce() = receipts.deleteDead()

    private suspend fun untilNextSweep(): Duration =
        runCatchingCancellable {
            val lastRunMs =
                settings?.getValue(LAST_RUN_KEY)?.toLongOrNull() ?: return@runCatchingCancellable Duration.ZERO
            val elapsed = (clock.now().toEpochMilliseconds() - lastRunMs).milliseconds
            (interval - elapsed).coerceIn(Duration.ZERO, interval)
        }.onFailure { log.warn(it) { "MatchReceiptSweepTask could not read its last run; sweeping now" } }
            .getOrDefault(Duration.ZERO)

    private suspend fun recordSweep() {
        settings?.setValue(LAST_RUN_KEY, clock.now().toEpochMilliseconds().toString())
    }

    internal companion object {
        /** `server_settings` key holding the epoch-millis of the last successful sweep. */
        const val LAST_RUN_KEY = "scheduler.matchReceiptSweep.lastRunAtMs"

        private val START_JITTER = 10.seconds
    }
}
