package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.core.suspendRunCatching
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.remote.PlaybackApiContract
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.DownloadedBookSummary
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.download.DownloadEnqueuer
import com.calypsan.listenup.client.playback.AudioCapabilityDetector
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val logger = KotlinLogging.logger {}

/**
 * Single seam for download state + aggregation. All download writes go through this class
 * (Sync Engine Rule 5). Aggregation reducer lives here so platforms share the same state-machine.
 *
 * **Phase D scope:** state transitions + aggregation + reads + SSE-reconnect recheck +
 * app-startup recovery (24h backstop). Orchestration methods (`enqueueForBook`, `cancelForBook`)
 * throw [NotImplementedError] until Phase C/D moves platform code onto them.
 * `resumeForAudioFile` and `resumeIncompleteDownloads` are implemented via the [DownloadEnqueuer] seam.
 *
 * Cross-phase aliases per W8 design:
 * - [markCancelled] writes [DownloadState.CANCELLED] (Phase D real impl).
 */
class DownloadRepositoryImpl(
    private val downloadDao: DownloadDao,
    private val bookRepository: BookRepository,
    private val enqueuer: DownloadEnqueuer,
    private val playbackApi: PlaybackApiContract,
    private val playbackPreferences: PlaybackPreferences,
    private val capabilityDetector: AudioCapabilityDetector,
) : DownloadRepository {
    // --- Reads ---

    override fun observeForBook(bookId: BookId): Flow<List<DownloadEntity>> = downloadDao.observeForBook(bookId.value)

    override fun observeAll(): Flow<List<DownloadEntity>> = downloadDao.observeAll()

    override fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus> =
        downloadDao.observeForBook(bookId.value).map { aggregate(bookId.value, it) }

    override fun observeAllStatuses(): Flow<Map<String, BookDownloadStatus>> =
        downloadDao.observeAll().map { downloads ->
            downloads
                .groupBy { it.bookId }
                .mapValues { (bookId, files) -> aggregate(bookId, files) }
        }

    override fun observeDownloadedBooks(): Flow<List<DownloadedBookSummary>> =
        downloadDao.observeAll().map { downloads ->
            val completedByBook =
                downloads
                    .filter { it.state == DownloadState.COMPLETED }
                    .groupBy { it.bookId }

            if (completedByBook.isEmpty()) return@map emptyList()

            val books =
                bookRepository
                    .getBookListItems(completedByBook.keys.toList())
                    .associateBy { it.id.value }

            completedByBook
                .mapNotNull { (bookId, files) ->
                    val book = books[bookId] ?: return@mapNotNull null
                    DownloadedBookSummary(
                        bookId = bookId,
                        title = book.title,
                        authorNames = book.authorNames,
                        coverBlurHash = book.coverBlurHash,
                        sizeBytes = files.sumOf { it.downloadedBytes },
                        fileCount = files.size,
                    )
                }.sortedByDescending { it.sizeBytes }
        }

    override suspend fun getLocalPath(audioFileId: String): String? = downloadDao.getLocalPath(audioFileId)

    override suspend fun getStateForAudioFile(audioFileId: String): DownloadState? =
        downloadDao.getByAudioFileId(audioFileId)?.state

    // --- State-transition writes ---

    override suspend fun markDownloading(
        audioFileId: String,
        startedAt: Long,
    ): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.updateState(audioFileId, DownloadState.DOWNLOADING, startedAt)
        }

    override suspend fun updateProgress(
        audioFileId: String,
        downloadedBytes: Long,
        totalBytes: Long,
    ): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.updateProgress(audioFileId, downloadedBytes, totalBytes)
        }

    override suspend fun markCompleted(
        audioFileId: String,
        localPath: String,
        completedAt: Long,
    ): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.markCompleted(audioFileId, localPath, completedAt)
        }

    override suspend fun markPaused(audioFileId: String): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.updateState(audioFileId, DownloadState.PAUSED)
        }

    override suspend fun markCancelled(audioFileId: String): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.updateState(audioFileId, DownloadState.CANCELLED)
        }

    override suspend fun markFailed(
        audioFileId: String,
        error: DownloadError,
    ): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.updateError(audioFileId, error.message)
        }

    override suspend fun markWaitingForServer(
        audioFileId: String,
        transcodeJobId: String,
    ): AppResult<Unit> =
        suspendRunCatching {
            downloadDao.markWaitingForServer(audioFileId, transcodeJobId)
        }

    // --- Orchestration (Phase B: stub; Phase C/D move platform code onto these) ---

    @Suppress("NotImplementedDeclaration") // Phase C/D scope — intentional stub per W8 design
    override suspend fun enqueueForBook(bookId: BookId): AppResult<DownloadOutcome> =
        throw NotImplementedError(
            "DownloadRepository.enqueueForBook is Phase C/D scope. Phase B keeps " +
                "orchestration on DownloadManager (Android) and AppleDownloadService (iOS).",
        )

    override suspend fun cancelForBook(bookId: BookId): AppResult<Unit> =
        suspendRunCatching {
            val rows = downloadDao.getForBook(bookId.value)

            // For WAITING_FOR_SERVER rows, tell the server to stop transcoding (Q8 B1).
            // Failures are logged but not fatal — local state still transitions to CANCELLED below.
            for (row in rows) {
                if (row.state == DownloadState.WAITING_FOR_SERVER) {
                    val jobId = row.transcodeJobId ?: continue
                    val result = playbackApi.cancelTranscode(jobId)
                    if (result is AppResult.Failure) {
                        logger.warn {
                            "cancelTranscode failed for jobId=$jobId: ${result.error.message} — continuing with local cancel"
                        }
                    }
                }
            }

            // Transition all non-terminal rows to CANCELLED.
            for (row in rows) {
                if (row.state != DownloadState.COMPLETED && row.state != DownloadState.DELETED) {
                    markCancelled(row.audioFileId)
                }
            }
        }

    override suspend fun deleteForBook(bookId: String) {
        downloadDao.deleteForBook(bookId)
    }

    override suspend fun resumeForAudioFile(audioFileId: String): AppResult<Unit> {
        val entity =
            downloadDao.getByAudioFileId(audioFileId)
                ?: return AppResult.Failure(
                    DownloadError.DownloadFailed(
                        debugInfo = "No download row for $audioFileId",
                    ),
                )

        // Late SSE event for a cancelled or completed row — silently drop (defense-in-depth net).
        if (entity.state == DownloadState.CANCELLED || entity.state == DownloadState.COMPLETED) {
            return AppResult.Success(Unit)
        }

        return enqueuer.enqueue(entity)
    }

    override suspend fun resumeIncompleteDownloads(): AppResult<Unit> =
        suspendRunCatching {
            val now = currentEpochMilliseconds()
            val backstopMs = 24L * 60L * 60L * 1000L // 24 hours

            // 24h backstop: any WAITING_FOR_SERVER row older than 24h is stuck — mark FAILED.
            val staleWaiting = downloadDao.getOldWaitingForServer(thresholdMs = now - backstopMs)
            for (row in staleWaiting) {
                markFailed(
                    row.audioFileId,
                    DownloadError.TranscodeTimeout(
                        transcodeJobId = row.transcodeJobId ?: "unknown",
                    ),
                )
            }

            // Re-enqueue incomplete (non-stale) downloads via the platform enqueuer.
            // Note: existing DownloadManager.resumeIncompleteDownloads also runs at app startup and is
            // the primary recovery path; this method exists for parity. Phase E may consolidate.
            val incomplete = downloadDao.getIncomplete()
            for (row in incomplete) {
                // Skip rows we just timed out above.
                if (row.state == DownloadState.WAITING_FOR_SERVER &&
                    row.startedAt != null &&
                    row.startedAt < now - backstopMs
                ) {
                    continue
                }
                enqueuer.enqueue(row)
            }
        }

    override suspend fun recheckWaitingForServer(): AppResult<Unit> =
        suspendRunCatching {
            val rows = downloadDao.getWaitingForServer()
            if (rows.isEmpty()) return@suspendRunCatching

            val capabilities = capabilityDetector.getSupportedCodecs()
            val spatial = playbackPreferences.getSpatialPlayback()

            for (row in rows) {
                val result = playbackApi.preparePlayback(row.bookId, row.audioFileId, capabilities, spatial)
                when (result) {
                    is AppResult.Success -> {
                        val response = result.data
                        when {
                            response.ready -> {
                                // Transcode finished during disconnect — re-enqueue.
                                resumeForAudioFile(row.audioFileId)
                            }

                            response.transcodeJobId == null -> {
                                // Server lost the job — mark failed so user can retry.
                                markFailed(
                                    row.audioFileId,
                                    DownloadError.TranscodeTimeout(
                                        transcodeJobId = row.transcodeJobId ?: "unknown",
                                    ),
                                )
                            }

                            else -> {
                                // Still transcoding — leave alone; next reconnect or transcode.complete will pick it up.
                            }
                        }
                    }

                    is AppResult.Failure -> {
                        // Network / server error during recheck — leave WAITING_FOR_SERVER alone;
                        // user can manually retry or wait for next reconnect.
                    }
                }
            }
        }

    // --- Aggregation reducer (shared across platforms) ---

    private fun aggregate(
        bookId: String,
        downloads: List<DownloadEntity>,
    ): BookDownloadStatus {
        if (downloads.isEmpty()) {
            return BookDownloadStatus.NotDownloaded(bookId)
        }
        val activeDownloads =
            downloads.filter {
                it.state != DownloadState.DELETED && it.state != DownloadState.CANCELLED
            }
        if (activeDownloads.isEmpty()) {
            return BookDownloadStatus.NotDownloaded(bookId)
        }
        val totalFiles = activeDownloads.size
        val completedFiles = activeDownloads.count { it.state == DownloadState.COMPLETED }
        val totalBytes = activeDownloads.sumOf { it.totalBytes }
        val downloadedBytes = activeDownloads.sumOf { it.downloadedBytes }
        return when {
            activeDownloads.all { it.state == DownloadState.COMPLETED } -> {
                BookDownloadStatus.Completed(bookId = bookId, totalBytes = totalBytes)
            }

            activeDownloads.any { it.state == DownloadState.FAILED } -> {
                BookDownloadStatus.Failed(
                    bookId = bookId,
                    errorMessage =
                        activeDownloads
                            .firstOrNull { it.state == DownloadState.FAILED }
                            ?.errorMessage
                            ?: "Download failed",
                    partiallyDownloadedFiles = completedFiles,
                )
            }

            activeDownloads.all { it.state == DownloadState.PAUSED } -> {
                BookDownloadStatus.Paused(
                    bookId = bookId,
                    pausedFiles = activeDownloads.size,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                )
            }

            else -> {
                BookDownloadStatus.InProgress(
                    bookId = bookId,
                    totalFiles = totalFiles,
                    downloadingFiles = activeDownloads.count { it.state == DownloadState.DOWNLOADING },
                    waitingForServerFiles = activeDownloads.count { it.state == DownloadState.WAITING_FOR_SERVER },
                    completedFiles = completedFiles,
                    totalBytes = totalBytes,
                    downloadedBytes = downloadedBytes,
                )
            }
        }
    }
}
