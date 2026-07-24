package com.calypsan.listenup.client.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.SyncRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import com.calypsan.listenup.api.result.AppResult as CoreResult

private val logger = KotlinLogging.logger {}

/**
 * Android WorkManager worker for background sync operations.
 *
 * Executes sync via [SyncRepository] when triggered by WorkManager.
 * Scheduling is handled by [AndroidBackgroundSyncScheduler].
 *
 * Retry policy: Exponential backoff with 3 max retries.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {
    private val syncRepository: SyncRepository by inject()
    private val serverConfig: ServerConfig by inject()

    /**
     * Perform background sync operation.
     *
     * Called by WorkManager on a background thread. Uses [SyncRepository.refresh], NOT `sync()`:
     * `sync()` only calls `startEngineForCurrentUser()`, which **no-ops when the engine is already
     * running for this user** — the common case for a backgrounded-but-alive process. So the
     * periodic worker did nothing at all in that state: no catch-up, no digest reconcile, no outbox
     * drain. `refresh()` forces a full lifecycle reconcile (forward catch-up → digest → refresh
     * strategies, bypassing the debounce) — and that reconcile now also drives the pending-operation
     * outbox — so a background wake actually pulls missed changes AND pushes parked local edits.
     */
    override suspend fun doWork(): Result {
        logger.debug { "Starting background sync (attempt ${runAttemptCount + 1})" }

        // Skip sync if no server is configured (fresh install)
        if (!serverConfig.hasServerConfigured()) {
            logger.debug { "No server configured, skipping background sync" }
            return Result.success()
        }

        return when (val result = syncRepository.refresh()) {
            is CoreResult.Success<*> -> {
                logger.info { "Background sync completed successfully" }
                Result.success()
            }

            is CoreResult.Failure -> {
                logger.error { "Background sync failed: ${result.message}" }

                // Retry with exponential backoff up to 3 times
                if (runAttemptCount < 3) {
                    logger.debug { "Will retry sync (attempt ${runAttemptCount + 1}/3)" }
                    Result.retry()
                } else {
                    logger.warn { "Max retries reached, giving up" }
                    Result.failure()
                }
            }
        }
    }
}
