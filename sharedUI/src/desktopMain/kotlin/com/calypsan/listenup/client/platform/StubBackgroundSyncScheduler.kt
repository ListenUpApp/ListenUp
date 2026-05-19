package com.calypsan.listenup.client.platform

import com.calypsan.listenup.client.sync.BackgroundSyncScheduler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Stub implementation of [BackgroundSyncScheduler] for desktop.
 *
 * Background sync scheduling is not yet implemented on desktop.
 * The app relies on SSE for real-time updates while running.
 *
 * TODO: Consider implementing periodic sync using a Timer or coroutine job
 * that runs while the app is open.
 */
class StubBackgroundSyncScheduler : BackgroundSyncScheduler {
    override fun schedule() {
        logger.info { "Background sync scheduling requested (not yet implemented on desktop)" }
        // Desktop apps typically stay running, so SSE provides real-time sync
        // Periodic background sync could be added using a coroutine timer
    }

    override fun cancel() {
        logger.info { "Background sync cancellation requested (not yet implemented on desktop)" }
    }
}
