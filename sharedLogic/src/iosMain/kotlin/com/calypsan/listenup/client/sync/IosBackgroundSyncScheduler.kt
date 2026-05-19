@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.sync

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateByAddingTimeInterval

private val logger = KotlinLogging.logger {}

/**
 * iOS implementation of [BackgroundSyncScheduler].
 *
 * Uses BGTaskScheduler to schedule periodic app refresh tasks.
 *
 * Requirements for iOS background tasks:
 * 1. Add "Background fetch" capability in Xcode
 * 2. Add task identifier to Info.plist under BGTaskSchedulerPermittedIdentifiers
 * 3. Register task handler in AppDelegate/App using [registerTaskHandler]
 *
 * The task handler calls [com.calypsan.listenup.client.data.sync.SyncManager.sync]
 * when iOS wakes the app for background refresh.
 */
class IosBackgroundSyncScheduler : BackgroundSyncScheduler {
    companion object {
        /**
         * Task identifier - must match Info.plist BGTaskSchedulerPermittedIdentifiers.
         */
        const val TASK_IDENTIFIER = "com.calypsan.listenup.sync"

        /**
         * Minimum interval between background syncs (15 minutes).
         * iOS may delay execution based on system conditions.
         */
        private const val SYNC_INTERVAL_SECONDS = 15.0 * 60.0
    }

    /**
     * Schedule background app refresh.
     *
     * Submits a BGAppRefreshTaskRequest to run sync periodically.
     * iOS determines actual execution time based on app usage patterns,
     * battery state, and network availability.
     */
    override fun schedule() {
        logger.info { "Scheduling iOS background sync" }

        val request =
            BGAppRefreshTaskRequest(identifier = TASK_IDENTIFIER).apply {
                // Request execution no earlier than 15 minutes from now
                earliestBeginDate = NSDate().dateByAddingTimeInterval(SYNC_INTERVAL_SECONDS)
            }

        try {
            val success = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
            if (success) {
                logger.info { "Background sync scheduled" }
            } else {
                logger.warn { "Failed to schedule background sync" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error scheduling background sync" }
        }
    }

    /**
     * Cancel all pending background sync tasks.
     */
    override fun cancel() {
        logger.info { "Cancelling iOS background sync" }
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(TASK_IDENTIFIER)
    }
}
