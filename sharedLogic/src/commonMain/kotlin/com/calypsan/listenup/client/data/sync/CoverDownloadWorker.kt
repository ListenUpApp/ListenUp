package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.data.local.db.CoverDownloadDao
import com.calypsan.listenup.client.data.local.db.CoverDownloadStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.calypsan.listenup.client.core.Failure

private val logger = KotlinLogging.logger {}

private const val COVER_DOWNLOAD_DELAY_MS = 500L

/**
 * Processes the persistent cover download queue.
 *
 * Unlike the old fire-and-forget coroutine approach, this worker:
 * - Reads tasks from a Room-backed queue that survives app lifecycle
 * - Processes one at a time (individual downloads, not batch tar)
 * - Marks each task as completed/failed in the DB
 * - Can be stopped and resumed at any point
 * - Tracks progress for UI display
 *
 * Triggered by: initial sync, app resume, manual refresh, SSE cover change.
 */
class CoverDownloadWorker(
    private val coverDownloadDao: CoverDownloadDao,
    private val imageDownloader: ImageDownloaderContract,
) {
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    /** Remaining tasks (pending + retryable failed). */
    val remainingCount: Flow<Int> = coverDownloadDao.observeRemainingCount()

    /** Completed tasks. */
    val completedCount: Flow<Int> = coverDownloadDao.observeCompletedCount()

    /** Total tasks ever enqueued (for progress denominator). */
    val totalCount: Flow<Int> = coverDownloadDao.observeTotalCount()

    /**
     * Reset any tasks that were IN_PROGRESS when the app was killed.
     * Call this on app startup before processing.
     */
    suspend fun recoverInterrupted() {
        coverDownloadDao.resetInProgress()
    }

    /**
     * Process the download queue until empty or cancelled.
     *
     * Downloads covers one at a time using individual requests (not batch tar).
     * Each successful download is immediately marked COMPLETED in Room.
     * Failed downloads are marked FAILED with attempt count incremented.
     *
     * This method is safe to call multiple times — it's a no-op if already processing
     * or if the queue is empty.
     */
    @Suppress("NestedBlockDepth")
    suspend fun processQueue() {
        if (_isProcessing.value) {
            logger.debug { "Cover download worker already processing, skipping" }
            return
        }

        _isProcessing.value = true
        logger.info { "Cover download worker started" }

        try {
            var processedCount = 0

            while (true) {
                val batch = coverDownloadDao.getNextBatch(limit = 5)
                if (batch.isEmpty()) break

                for (task in batch) {
                    try {
                        coverDownloadDao.markInProgress(task.bookId)

                        val result = imageDownloader.downloadCover(task.bookId)

                        when {
                            result is AppResult.Success && result.data -> {
                                // Cover downloaded and saved
                                coverDownloadDao.markCompleted(task.bookId)
                                processedCount++

                                // Extract and save palette colors
                                // (color extraction happens inside imageDownloader.downloadCover)
                                logger.debug { "Cover downloaded: ${task.bookId.value}" }
                            }

                            result is AppResult.Success && !result.data -> {
                                // Cover already exists locally or not available on server
                                coverDownloadDao.markCompleted(task.bookId)
                                processedCount++
                            }

                            result is AppResult.Failure -> {
                                coverDownloadDao.markFailed(
                                    task.bookId,
                                    result.message,
                                )
                                logger.debug {
                                    "Cover download failed: ${task.bookId.value} " +
                                        "(attempt ${task.attempts + 1}): ${result.message}"
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        // App is backgrounding — mark task back to pending so it resumes later
                        coverDownloadDao.updateStatus(task.bookId, CoverDownloadStatus.PENDING)
                        throw e
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        coverDownloadDao.markFailed(task.bookId, e.message)
                        logger.warn(e) { "Cover download error: ${task.bookId.value}" }
                    }

                    delay(COVER_DOWNLOAD_DELAY_MS)
                }
            }

            logger.info { "Cover download worker finished: $processedCount covers processed" }
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Clean up old completed tasks to prevent unbounded queue growth.
     */
    suspend fun purgeOldCompleted() {
        // Remove completed tasks older than 7 days
        val sevenDaysAgo = Timestamp(Timestamp.now().epochMillis - 7 * 24 * 60 * 60 * 1000L)
        coverDownloadDao.purgeCompleted(sevenDaysAgo)
    }
}
