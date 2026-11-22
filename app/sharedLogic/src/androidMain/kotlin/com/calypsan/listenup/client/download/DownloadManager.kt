package com.calypsan.listenup.client.download

import androidx.work.WorkManager
import androidx.work.await
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.heldRefusal
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import com.calypsan.listenup.core.IODispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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
 * A book downloads as one run ([BookTransfers]): a user-initiated data transfer job on Android 14+, a `dataSync`
 * foreground worker below it. Runs survive the app leaving the screen, take their turn one book at a time, show a
 * notification with Pause and Cancel, and record every stop with its reason.
 */
class DownloadManager internal constructor(
    private val downloadDao: DownloadDao,
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val workManager: WorkManager,
    private val fileManager: DownloadFileManager,
    private val downloadRepository: DownloadRepository,
    private val transactionRunner: TransactionRunner,
    private val errorBus: ErrorBus,
    private val transfers: BookTransfers,
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
        // A held book is triage-only (spec §8): it can't be downloaded until an admin releases it.
        bookDao.heldRefusal(bookId)?.let { refusal ->
            logger.info { "Refusing to download ${bookId.value}: held for review" }
            return AppResult.Failure(refusal)
        }
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
                .filter { download ->
                    download.state == DownloadState.COMPLETED ||
                        download.state == DownloadState.DOWNLOADING ||
                        download.state == DownloadState.QUEUED
                }.map { it.audioFileId }
                .toSet()

        val toDownload = audioFiles.filterNot { it.id in activeIds }

        if (toDownload.isEmpty()) {
            logger.info { "All files already downloading, queued, or completed for ${bookId.value}" }
            // A tap is the moment a run can be scheduled; make sure the queued files have one (a no-op when they do).
            transfers.schedule(bookId.value)
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

        // A paused or failed file keeps the bytes its .tmp already holds (the insert REPLACEs its row).
        val entities =
            queuedDownloadRows(
                bookId = bookId.value,
                audioFiles = audioFiles,
                toDownload = toDownload,
                existing = existing,
                queuedAt = System.currentTimeMillis(),
            )

        // Persistence Rule 1: insert is transactional. resumeIncompleteDownloads is the
        // documented recovery path for crash-between-commit-and-enqueue.
        transactionRunner.atomically {
            downloadDao.insertAll(entities)
        }

        // One run per book. The tap happens with the app on screen, which is when Android 14+ allows a
        // user-initiated job to be scheduled. A run already registered for this book picks the new rows up.
        transfers.schedule(bookId.value)

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
        // Stop every run first, so none is still writing into the directory being wiped.
        downloadDao
            .getIncomplete()
            .map { it.bookId }
            .distinct()
            .forEach { transfers.cancelRun(it) }
        fileManager.deleteAllFiles()
        downloadDao.deleteAll()
        logger.info { "Deleted all downloads (files + records)" }
    }

    /**
     * Cancel a book's download and discard what it saved, so it reads "not downloaded" again. The rows are cancelled
     * before the run is stopped, so a file that lands in the meantime is discarded rather than completed.
     */
    override suspend fun cancelDownload(bookId: BookId) {
        workManager.cancelAllWorkByTag(legacyBookTag(bookId.value)).await()
        transfers.cancel(bookId.value)
    }

    /**
     * Pause a book's download for the person: its partial files stay, and only they resume it (Resume is
     * [downloadBook], which carries on from the saved bytes). [fromNotification] keeps a "Download paused" notification
     * with Resume; a pause from inside the app withdraws the notification.
     */
    override suspend fun pauseDownload(bookId: BookId) {
        pauseDownload(bookId, fromNotification = false)
    }

    /** [pauseDownload], saying where it came from: a pause from the notification keeps a notification with Resume. */
    suspend fun pauseDownload(
        bookId: BookId,
        fromNotification: Boolean,
    ) {
        transfers.pause(bookId.value, fromNotification)
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
        workManager.cancelAllWorkByTag(legacyBookTag(bookId.value)).await()
        transfers.cancelRun(bookId.value)

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
     * Resume any incomplete downloads (after sign-in, an app start, or the app returning to the screen). Sweeps orphan
     * `.tmp` partials, cancels the per-file work of earlier releases, and gives every book with something to transfer
     * a run unless it already has one ([BookTransfers.resumeAll]). On Android 14+ a run can only be scheduled while the
     * app is visible; a start in the background records the refusal and the next foreground schedules it.
     */
    override suspend fun resumeIncompleteDownloads() {
        val incomplete = downloadDao.getIncomplete()

        // Sweep orphaned .tmp partials from prior runs: any .tmp whose audioFileId is not in the
        // active (non-terminal) set is a leftover from a cancelled or crashed download.
        // A file the person paused is left out of `incomplete` (it waits for them, not for a launch), but its
        // partial is what Resume continues from, so it is kept too.
        val userPausedIds =
            downloadDao
                .observeAll()
                .first()
                .filter { row ->
                    row.state == DownloadState.PAUSED &&
                        row.pauseReason == DownloadPauseReason.PAUSED_BY_YOU
                }.map { row -> row.audioFileId }
        val activeIds = incomplete.map { it.audioFileId }.toSet() + userPausedIds
        // The sweep is a blocking recursive disk walk, so it runs off the caller's dispatcher.
        val swept = withContext(IODispatcher) { fileManager.sweepOrphanedTempFiles(activeIds) }
        if (swept > 0) {
            logger.info { "Swept $swept orphaned .tmp file(s) on startup" }
        }

        if (incomplete.isEmpty()) return

        // The per-file workers of earlier releases: cancelled once, their books scheduled as runs below.
        incomplete
            .map { it.bookId }
            .distinct()
            .forEach { workManager.cancelAllWorkByTag(legacyBookTag(it)).await() }

        resumeScheduling()
    }

    /** Give every book with something to transfer a run unless it has one; cheap enough for every foreground. */
    suspend fun resumeScheduling() {
        transfers.resumeAll()
    }

    /**
     * Bring every unfinished download onto the current Wi-Fi-only rule. The platform fixes a job's network rule when
     * it is scheduled, so a change is honoured by replacing each book's run: a running one stops (recorded as a
     * preference change), keeps its partial, and the replacement resumes from those bytes under the new rule.
     */
    internal suspend fun reapplyNetworkConstraints(wifiOnly: Boolean) {
        logger.info { "download: network rule changed wifiOnly=$wifiOnly" }
        transfers.reapplyNetworkRule()
    }
}
