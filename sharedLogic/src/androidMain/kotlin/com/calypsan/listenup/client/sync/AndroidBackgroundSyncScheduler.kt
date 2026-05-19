package com.calypsan.listenup.client.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

private val logger = KotlinLogging.logger {}

/**
 * Android implementation of [BackgroundSyncScheduler].
 *
 * Uses WorkManager to schedule periodic sync every 15 minutes when
 * network is available. WorkManager handles:
 * - Surviving app restarts
 * - Battery optimization
 * - Doze mode compatibility
 * - Constraint-based execution
 */
class AndroidBackgroundSyncScheduler(
    private val context: Context,
) : BackgroundSyncScheduler {
    companion object {
        private const val WORK_NAME = "periodic_sync"
        private val SYNC_INTERVAL = 15.minutes
    }

    /**
     * Schedule periodic background sync via WorkManager.
     *
     * Uses KEEP policy to avoid restarting if already scheduled.
     * Requires network connectivity but allows any network type.
     */
    override fun schedule() {
        logger.info { "Scheduling periodic sync every $SYNC_INTERVAL" }

        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val syncRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(
                repeatInterval = SYNC_INTERVAL.toJavaDuration(),
            ).setConstraints(constraints)
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest,
        )

        logger.info { "Periodic sync scheduled" }
    }

    /**
     * Cancel all scheduled sync work.
     */
    override fun cancel() {
        logger.info { "Cancelling periodic sync" }
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
