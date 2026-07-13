package com.calypsan.listenup.client.download

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow

private val logger = KotlinLogging.logger {}

/**
 * Manages audiobook downloads.
 *
 * Responsibilities:
 * - Queue/cancel/delete downloads
 * - Track download state per book
 * - Resolve local paths for offline playback
 * - Calculate storage usage
 * - Respect WiFi-only download constraint via WorkManager
 *
 * Implements [DownloadService] for use by shared code (PlaybackManager).
 *
 * The download queue is implemented via WorkManager's work queue, which:
 * - Persists queued downloads across app restarts
 * - Automatically retries failed downloads with exponential backoff
 * - Respects network constraints (WiFi-only when enabled)
 * - Allows cancellation via unique work names
 */
class DownloadManager internal constructor(
    private val downloadDao: DownloadDao,
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val workManager: WorkManager,
    private val fileManager: DownloadFileManager,
    private val localPreferences: com.calypsan.listenup.client.domain.repository.LocalPreferences,
    private val downloadRepository: DownloadRepository,
    private val transactionRunner: TransactionRunner,
) : DownloadService {
    companion object {
        private const val STORAGE_BUFFER_MULTIPLIER = 1.1 // 10% buffer for download size estimates
        private const val DECIMAL_BYTES_PER_MB = 1_000_000L
    }

    /**
     * Observe download status for a specific book.
     */
    override fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus> =
        downloadRepository.observeBookStatus(bookId)

    override fun observeAllStatuses(): Flow<Map<String, BookDownloadStatus>> = downloadRepository.observeAllStatuses()

    /**
     * Download a book (queue all audio files).
     * Called when user taps download button OR starts streaming.
     *
     * @return AppResult indicating success, failure reason, or if already downloaded
     */
    override suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome> {
        // Check if already downloading or downloaded
        val existing = downloadDao.getForBook(bookId.value)
        if (existing.isNotEmpty() && existing.all { it.state == DownloadState.COMPLETED }) {
            logger.info { "Book ${bookId.value} already downloaded" }
            return AppResult.Success(DownloadOutcome.AlreadyDownloaded)
        }

        // Verify book exists before attempting download
        if (bookDao.getById(bookId) == null) {
            logger.error { "Book not found: ${bookId.value}" }
            return AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "Book not found"))
        }

        val audioFiles: List<AudioFileEntity> = audioFileDao.getForBook(bookId.value)
        if (audioFiles.isEmpty()) {
            logger.warn { "No audio files for book ${bookId.value}" }
            return AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "No audio files available"))
        }

        // Skip files that are already completed OR have an in-flight row (downloading/queued). A
        // running download must NOT be re-inserted (REPLACE would reset its bytes to 0) or re-enqueued
        // (REPLACE work policy would cancel its worker). PlaybackPreparer calls downloadBook on every
        // prepare of a not-fully-downloaded book, so pressing play mid-download would otherwise stomp
        // in-flight progress. Mirrors the iOS AppleDownloadService skip-set.
        val activeIds =
            existing
                .filter {
                    it.state == DownloadState.COMPLETED ||
                        it.state == DownloadState.DOWNLOADING ||
                        it.state == DownloadState.QUEUED
                }.map { it.audioFileId }
                .toSet()

        val toDownload = audioFiles.filterNot { it.id in activeIds }

        if (toDownload.isEmpty()) {
            logger.info { "All files already downloading, queued, or completed for ${bookId.value}" }
            return AppResult.Success(DownloadOutcome.AlreadyDownloaded)
        }

        // Check available storage before queueing downloads
        val requiredBytes = toDownload.sumOf { it.size }
        val availableBytes = fileManager.getAvailableSpace()
        // Add 10% buffer for safety
        val requiredWithBuffer = (requiredBytes * STORAGE_BUFFER_MULTIPLIER).toLong()

        if (availableBytes < requiredWithBuffer) {
            logger.warn {
                "Insufficient storage for book ${bookId.value}: " +
                    "need ${requiredBytes / DECIMAL_BYTES_PER_MB}MB, have ${availableBytes / DECIMAL_BYTES_PER_MB}MB"
            }
            return AppResult.Success(
                DownloadOutcome.InsufficientStorage(
                    requiredBytes = requiredBytes,
                    availableBytes = availableBytes,
                ),
            )
        }

        val now = System.currentTimeMillis()
        val entities =
            toDownload.mapIndexed { _, file ->
                DownloadEntity(
                    audioFileId = file.id,
                    bookId = bookId.value,
                    filename = file.filename,
                    fileIndex = audioFiles.indexOfFirst { it.id == file.id },
                    state = DownloadState.QUEUED,
                    localPath = null,
                    totalBytes = file.size,
                    downloadedBytes = 0,
                    queuedAt = now,
                    startedAt = null,
                    completedAt = null,
                    errorMessage = null,
                    retryCount = 0,
                )
            }

        // Persistence Rule 1: insert is transactional. resumeIncompleteDownloads is the
        // documented recovery path for crash-between-commit-and-enqueue.
        transactionRunner.atomically {
            downloadDao.insertAll(entities)
        }

        // Determine network constraint based on WiFi-only preference
        // UNMETERED = WiFi/ethernet only, CONNECTED = any network (including cellular)
        val wifiOnly = localPreferences.wifiOnlyDownloads.value
        val requiredNetworkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        logger.info {
            "Queueing downloads with network constraint: " +
                if (wifiOnly) "UNMETERED (WiFi only)" else "CONNECTED (any network)"
        }

        // Queue WorkManager jobs
        toDownload.forEach { file ->
            val workRequest =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(
                        workDataOf(
                            DownloadWorker.KEY_AUDIO_FILE_ID to file.id,
                            DownloadWorker.KEY_BOOK_ID to bookId.value,
                            DownloadWorker.KEY_FILENAME to file.filename,
                            DownloadWorker.KEY_FILE_SIZE to file.size,
                        ),
                    ).setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(requiredNetworkType)
                            .build(),
                    ).addTag(bookTag(bookId))
                    .addTag(fileCancelTag(file.id))
                    .build()

            // KEEP (not REPLACE): never displace a worker already running for this file. toDownload
            // already excludes in-flight rows, so KEEP only ever affects retried FAILED/PAUSED files,
            // whose prior work has finished and won't block the fresh enqueue.
            workManager.enqueueUniqueWork(
                fileWorkName(file.id),
                ExistingWorkPolicy.KEEP,
                workRequest,
            )
        }

        logger.info { "Queued ${toDownload.size} files for download: ${bookId.value}" }
        return AppResult.Success(DownloadOutcome.Started)
    }

    /**
     * Wipe every downloaded file and every download record ("Delete All Downloads").
     *
     * Reclaims orphaned files/rows that per-book deletion can't reach. A worker that finishes after
     * this returns can only issue UPDATEs, which no-op against the now-empty table — no row resurrects.
     */
    override suspend fun deleteAllDownloads() {
        fileManager.deleteAllFiles()
        downloadDao.deleteAll()
        logger.info { "Deleted all downloads (files + records)" }
    }

    /**
     * Cancel active download for a book.
     */
    override suspend fun cancelDownload(bookId: BookId) {
        // Await WorkManager cancellation completion before updating DB state. Closes the race
        // where a worker's final updateProgress write lands after cancelAllWorkByTag returns
        // but before the state update fires.
        workManager.cancelAllWorkByTag(bookTag(bookId)).await()
        downloadRepository
            .cancelForBook(bookId)
            .onFailure { logger.warn { "Failed to persist cancelled state: ${bookId.value}" } }
        logger.info { "Cancelled download: ${bookId.value}" }
    }

    /**
     * Delete downloaded files for a book.
     * Marks records as DELETED (keeps them for tracking) and removes files.
     * This prevents auto-download on next playback - user must explicitly tap download.
     */
    override suspend fun deleteDownload(bookId: BookId) {
        // Cancel any active downloads first
        // Await WorkManager cancellation completion before deleting files. Closes the race
        // where a worker's final updateProgress write lands after cancelAllWorkByTag returns
        // but before the deletion fires.
        workManager.cancelAllWorkByTag(bookTag(bookId)).await()

        // Delete files from disk
        fileManager.deleteBookFiles(bookId.value)

        // Mark as deleted (don't remove records - used to track explicit deletion)
        downloadDao.markDeletedForBook(bookId.value)

        logger.info { "Deleted download: ${bookId.value}" }
    }

    /**
     * Check if a book was explicitly deleted by user.
     * Used to determine if we should auto-download on playback.
     */
    override suspend fun wasExplicitlyDeleted(bookId: BookId): Boolean = downloadDao.hasDeletedRecords(bookId.value)

    /**
     * Get local file path for an audio file (if downloaded).
     * Returns null if not downloaded or file missing.
     * If file was deleted externally, cleans up database entry.
     */
    override suspend fun getLocalPath(audioFileId: String): String? {
        val path = downloadDao.getLocalPath(audioFileId) ?: return null

        // Verify file still exists
        if (fileManager.fileExists(path)) return path

        // File was deleted externally - clean up database to stay consistent
        logger.warn { "Downloaded file missing, cleaning up: $audioFileId" }
        downloadDao.updateError(audioFileId, "File missing - deleted externally")
        return null
    }

    /**
     * Resume any incomplete downloads (e.g. after re-authentication or app restart).
     * Resets stalled states to QUEUED and re-enqueues via WorkManager with KEEP policy
     * so already-running work is not restarted.
     */
    override suspend fun resumeIncompleteDownloads() {
        val incomplete = downloadDao.getIncomplete()

        // Sweep orphaned .tmp partials from prior runs: any .tmp whose audioFileId is not in the
        // active (non-terminal) set is a leftover from a cancelled or crashed download.
        val activeIds = incomplete.map { it.audioFileId }.toSet()
        val swept = fileManager.sweepOrphanedTempFiles(activeIds)
        if (swept > 0) {
            logger.info { "Swept $swept orphaned .tmp file(s) on startup" }
        }

        if (incomplete.isEmpty()) return

        logger.info { "Resuming ${incomplete.size} incomplete downloads" }

        val wifiOnly = localPreferences.wifiOnlyDownloads.value
        val requiredNetworkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        for (download in incomplete) {
            // KEEP policy avoids displacing a worker that's already running for this row.
            // Only PAUSED is reset — DOWNLOADING rows belong to a worker that owns state transitions.
            if (download.state == DownloadState.PAUSED) {
                downloadDao.updateState(download.audioFileId, DownloadState.QUEUED)
            }

            val workRequest =
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(
                        workDataOf(
                            DownloadWorker.KEY_AUDIO_FILE_ID to download.audioFileId,
                            DownloadWorker.KEY_BOOK_ID to download.bookId,
                            DownloadWorker.KEY_FILENAME to download.filename,
                            DownloadWorker.KEY_FILE_SIZE to download.totalBytes,
                        ),
                    ).setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(requiredNetworkType)
                            .build(),
                    ).addTag(bookTag(download.bookId))
                    .addTag(fileCancelTag(download.audioFileId))
                    .build()

            workManager.enqueueUniqueWork(
                fileWorkName(download.audioFileId),
                ExistingWorkPolicy.KEEP,
                workRequest,
            )
        }

        logger.info { "Re-enqueued ${incomplete.size} incomplete downloads" }
    }

    // --- WorkManager string identifiers ---
    // fileWorkName is the unique-work name for enqueueUniqueWork; fileCancelTag is for
    // addTag/cancelAllWorkByTag. They produce different strings on purpose — do not consolidate.

    private fun bookTag(bookId: BookId): String = bookTag(bookId.value)

    private fun bookTag(bookIdValue: String): String = "download_$bookIdValue"

    private fun fileCancelTag(audioFileId: String): String = "download_file_$audioFileId"

    private fun fileWorkName(audioFileId: String): String = "download_$audioFileId"
}
