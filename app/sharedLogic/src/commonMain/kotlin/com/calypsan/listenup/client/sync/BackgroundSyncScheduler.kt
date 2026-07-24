package com.calypsan.listenup.client.sync

/**
 * Platform-agnostic interface for scheduling background sync.
 *
 * The actual sync logic lives in [com.calypsan.listenup.client.data.sync.SyncManager].
 * This interface only handles scheduling/cancellation of periodic background work.
 *
 * Platform implementations:
 * - Android: WorkManager with PeriodicWorkRequest
 * - iOS: BGTaskScheduler with BGAppRefreshTask
 */
interface BackgroundSyncScheduler {
    /**
     * Schedule periodic background sync.
     *
     * Safe to call multiple times - implementations should handle deduplication.
     * Typically called after successful authentication or on app launch.
     *
     * Sync will run periodically when:
     * - Network is available
     * - System determines it's a good time (battery, resources)
     */
    fun schedule()

    /**
     * Cancel all scheduled background sync work.
     *
     * Typically called on logout to stop syncing user data.
     */
    fun cancel()
}
