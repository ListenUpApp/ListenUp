package com.calypsan.listenup.client.download

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.await
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
import com.calypsan.listenup.core.error.ErrorBus
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
    private val errorBus: ErrorBus,
) : DownloadService {
    override val supportsDownloads: Boolean = true

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

        logger.info {
            "Queueing downloads with network constraint: " +
                if (wifiOnly) "UNMETERED (WiFi only)" else "CONNECTED (any network)"
        }

        // Queue WorkManager jobs
        entities.forEach { entity ->
            // KEEP (not REPLACE): never displace a worker already running for this file. toDownload
            // already excludes in-flight rows, so KEEP only ever affects retried FAILED/PAUSED files,
            // whose prior work has finished and won't block the fresh enqueue.
            workManager.enqueueUniqueWork(
                fileWorkName(entity.audioFileId),
                ExistingWorkPolicy.KEEP,
                buildDownloadRequest(entity, wifiOnly),
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
        workManager.cancelAllWorkByTag(bookTag(bookId.value)).await()
        downloadRepository
            .cancelForBook(bookId)
            .onFailure { logger.warn { "Failed to persist cancelled state: ${bookId.value}" } }
        logger.info { "Cancelled download: ${bookId.value}" }
    }

    /**
     * Delete downloaded files for a book.
     * Marks records as DELETED (keeps them for tracking) and removes files.
     * This prevents auto-download on next playback - user must explicitly tap download.
     *
     * Honest-over-silent (audit finding): a partial/failed file deletion must NOT be recorded as
     * a completed delete — that would orphan bytes on disk with no DB row pointing at them and no
     * way for the user to reclaim the space. [DownloadFileManager.deleteBookFiles]'s boolean
     * result gates the DB write; on failure the row is left DOWNLOADED so the listener can retry.
     */
    override suspend fun deleteDownload(bookId: BookId) {
        // Cancel any active downloads first
        // Await WorkManager cancellation completion before deleting files. Closes the race
        // where a worker's final updateProgress write lands after cancelAllWorkByTag returns
        // but before the deletion fires.
        workManager.cancelAllWorkByTag(bookTag(bookId.value)).await()

        // Delete files from disk
        if (!fileManager.deleteBookFiles(bookId.value)) {
            val bookTitle = bookDao.getById(bookId)?.title
            errorBus.emit(DownloadError.DeleteFailed(bookTitle = bookTitle))
            logger.error { "Failed to fully delete files for book: ${bookId.value}" }
            return
        }

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
     * Batched [getLocalPath]: one DB round trip via [DownloadRepository.getLocalPaths] instead of
     * one per file, then the same on-disk existence check + externally-deleted cleanup per
     * candidate as [getLocalPath].
     */
    override suspend fun getLocalPaths(audioFileIds: List<String>): Map<String, String> {
        if (audioFileIds.isEmpty()) return emptyMap()
        val completed = downloadRepository.getLocalPaths(audioFileIds)
        return buildMap {
            for ((audioFileId, path) in completed) {
                if (fileManager.fileExists(path)) {
                    put(audioFileId, path)
                } else {
                    logger.warn { "Downloaded file missing, cleaning up: $audioFileId" }
                    downloadDao.updateError(audioFileId, "File missing - deleted externally")
                }
            }
        }
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

        for (download in incomplete) {
            // KEEP policy avoids displacing a worker that's already running for this row.
            // Reset PAUSED and stale DOWNLOADING rows to QUEUED (B10a): at app startup no worker is
            // running, so a row left in DOWNLOADING after a crash would show "downloading" forever if
            // the (re-enqueued) worker is blocked by the wifi constraint. QUEUED reflects the truth
            // and lets the re-enqueue below drive it. The subsequent worker owns further transitions.
            if (download.state == DownloadState.PAUSED || download.state == DownloadState.DOWNLOADING) {
                downloadDao.updateState(download.audioFileId, DownloadState.QUEUED)
            }

            workManager.enqueueUniqueWork(
                fileWorkName(download.audioFileId),
                ExistingWorkPolicy.KEEP,
                buildDownloadRequest(download, wifiOnly),
            )
        }

        logger.info { "Re-enqueued ${incomplete.size} incomplete downloads" }
    }

    /**
     * Re-enqueue every non-terminal download under the current Wi-Fi-only policy.
     *
     * WorkManager bakes `Constraints` into the work request at enqueue time and offers no way to
     * amend them in place, so a preference change can only be honoured by replacing the work.
     * `REPLACE` (not `KEEP`) is required and is safe: `KEEP` would discard the new request and
     * leave the stale constraint, while cancelling a running worker routes through
     * `persistDownloadCancellation`, which marks the row PAUSED and KEEPS its `.tmp` partial —
     * the replacement worker resumes from those bytes via the `Range` header in
     * `downloadAudioFile`. No progress is lost; the download simply moves onto the right network.
     */
    internal suspend fun reapplyNetworkConstraints(wifiOnly: Boolean) {
        val rows = downloadDao.getIncomplete()
        if (rows.isEmpty()) return
        logger.info {
            "Re-applying network constraint to ${rows.size} download(s): " +
                if (wifiOnly) "UNMETERED (WiFi only)" else "CONNECTED (any network)"
        }
        constraintRefreshWork(rows, wifiOnly).forEach { (workName, request) ->
            workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
