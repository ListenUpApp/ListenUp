package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

private val logger = loggerFor<RescanScheduler>()

/**
 * Never-Stranded backstop: periodically full-rescans the registered library so
 * anything the live watcher missed (kernel OVERFLOW, a dropped mount, downtime)
 * is reconciled. Reuses the single-flight scan trigger, so a periodic pass that
 * collides with an in-flight scan is simply skipped.
 *
 * A non-positive [interval] disables the loop (start returns null).
 */
internal class RescanScheduler(
    private val scope: CoroutineScope,
    private val interval: Duration,
    private val libraryId: suspend () -> LibraryId?,
    private val rescan: suspend (LibraryId) -> Unit,
) {
    fun start(): Job? {
        if (interval <= Duration.ZERO) {
            logger.info { "periodic rescan disabled (interval <= 0)" }
            return null
        }
        logger.info { "periodic rescan enabled every $interval" }
        return scope.launch {
            while (isActive) {
                delay(interval)
                val id = libraryId() ?: continue
                try {
                    rescan(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.warn(e) { "periodic rescan failed for library ${id.value} — continuing" }
                }
            }
        }
    }
}
