package com.calypsan.listenup.client.sync

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * macOS implementation of [BackgroundSyncScheduler].
 *
 * Stub implementation — macOS background sync will be implemented later.
 * macOS apps can use NSBackgroundActivityScheduler or similar APIs.
 */
class MacosBackgroundSyncScheduler : BackgroundSyncScheduler {
    override fun schedule() {
        logger.info { "macOS background sync scheduling not yet implemented" }
    }

    override fun cancel() {
        logger.info { "macOS background sync cancellation not yet implemented" }
    }
}
