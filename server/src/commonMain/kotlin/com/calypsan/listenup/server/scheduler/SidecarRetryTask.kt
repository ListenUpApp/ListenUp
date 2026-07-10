package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.sidecar.SidecarWriter
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = loggerFor<SidecarRetryTask>()

/**
 * Periodic sweep that re-flushes `listenup.json` writes parked by an unwritable library
 * mount (see [SidecarWriter.retryPending]). A book whose write failed — network share
 * offline, read-only remount — stays in the writer's pending set; this sweep retries it
 * every [interval] until the mount recovers, so curation durability degrades to "delayed",
 * never "lost". Runs on the supplied [CoroutineScope]; cancel the returned [Job] to stop.
 * Re-raises `CancellationException`; suppresses other failures with a warning.
 */
internal class SidecarRetryTask(
    private val writer: SidecarWriter,
    private val interval: Duration = 5.minutes,
) {
    /** Start the sweep loop on [scope]. Returns the [Job] — cancel it to stop. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                delay(interval)
                runCatchingCancellable { writer.retryPending() }
                    .onFailure { log.warn(it) { "SidecarRetryTask sweep failed; will retry next interval" } }
            }
        }
}
