@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("MagicNumber")

package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.data.remote.model.AudioFileResponse
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.playback.AudioTokenProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setValue
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/**
 * iOS implementation of [DownloadService] using NSURLSession background downloads.
 *
 * **W10 carveout (W8 Phase B):** this class still writes directly to
 * [com.calypsan.listenup.client.data.local.db.DownloadDao] via the `downloadDao` constructor
 * parameter — Sync Engine Rule 5 violation, deferred to W10. The Android side migrated to inject
 * [com.calypsan.listenup.client.domain.repository.DownloadRepository] in W8 Phase B, but iOS is
 * part of W10 (iOS Player Adoption) which owns the iOS migration onto shared Kotlin VMs and
 * shared repositories. See `docs/superpowers/specs/2026-05-01-w8-handoff-design.md` § Phase B
 * "AppleDownloadService keeps its current DAO writes" and § Out of scope ("iOS download adoption
 * — deferred to W10").
 *
 * For Phase B specifically, this class's interface compliance was updated:
 * - [downloadBook] returns [com.calypsan.listenup.api.result.AppResult]<[com.calypsan.listenup.client.domain.model.DownloadOutcome]> instead of the legacy `DownloadResult`.
 * - [observeBookStatus] aggregates via the new sealed [com.calypsan.listenup.client.domain.model.BookDownloadStatus] hierarchy.
 *
 * The internal DAO writes remain untouched.
 *
 * **Codec negotiation: intentionally absent on iOS.** Android's `DownloadWorker.resolveDownloadUrl`
 * negotiates with the server's `preparePlayback` endpoint to pick a transcoded variant when the
 * device doesn't support the source codec. iOS's native AVFoundation audio player supports every
 * codec the server can serve (AAC, MP3, FLAC, ALAC, Opus), so negotiation is unnecessary — the
 * download URL points directly at the original file. **If a future server adds a codec iOS does
 * not natively support, this class requires codec negotiation matching the Android worker's
 * pattern.** Closes Finding 08 D6 (principled asymmetry, not silent omission).
 */
@OptIn(ExperimentalTime::class)
class AppleDownloadService(
    private val downloadDao: DownloadDao,
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val serverConfig: ServerConfig,
    private val tokenProvider: AudioTokenProvider,
    private val fileManager: DownloadFileManager,
    private val scope: CoroutineScope,
) : DownloadService {
    /**
     * Delegate handles download progress and completion.
     * Must be held as a strong reference (ObjC weak delegate pattern).
     */
    private val sessionDelegate = DownloadSessionDelegate(downloadDao, scope)

    private val urlSession: NSURLSession =
        run {
            val config = NSURLSessionConfiguration.defaultSessionConfiguration
            config.timeoutIntervalForRequest = 60.0
            config.timeoutIntervalForResource = 3600.0 // 1 hour for large files
            NSURLSession.sessionWithConfiguration(
                configuration = config,
                delegate = sessionDelegate,
                delegateQueue = null,
            )
        }

    override suspend fun getLocalPath(audioFileId: String): String? {
        val path = downloadDao.getLocalPath(audioFileId) ?: return null
        if (fileManager.fileExists(path)) return path
        logger.warn { "Downloaded file missing, cleaning up: $audioFileId" }
        downloadDao.updateError(audioFileId, "File missing - deleted externally")
        return null
    }

    override suspend fun wasExplicitlyDeleted(bookId: BookId): Boolean = downloadDao.hasDeletedRecords(bookId.value)

    @Suppress("ReturnCount")
    override suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome> {
        val existing = downloadDao.getForBook(bookId.value)
        if (existing.isNotEmpty() && existing.all { it.state == DownloadState.COMPLETED }) {
            return AppResult.Success(DownloadOutcome.AlreadyDownloaded)
        }

        val bookEntity =
            bookDao.getById(bookId) ?: run {
                logger.error { "Book not found: ${bookId.value}" }
                return AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "Book not found"))
            }

        val audioFileEntities = audioFileDao.getForBook(bookId.value)
        if (audioFileEntities.isEmpty()) {
            return AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "No audio files available"))
        }
        val audioFiles: List<AudioFileResponse> = audioFileEntities.map { it.toAudioFileResponse() }

        // Skip files already completed, downloading, or queued
        val activeIds =
            existing
                .filter { it.state in listOf(DownloadState.COMPLETED, DownloadState.DOWNLOADING, DownloadState.QUEUED) }
                .map { it.audioFileId }
                .toSet()

        val toDownload = audioFiles.filterNot { it.id in activeIds }

        if (toDownload.isEmpty()) {
            logger.info { "All files already downloading or completed for ${bookId.value}" }
            return AppResult.Success(DownloadOutcome.AlreadyDownloaded)
        }

        // Check storage
        val requiredBytes = toDownload.sumOf { it.size }
        val availableBytes = fileManager.getAvailableSpace()
        if (availableBytes < (requiredBytes * 1.1).toLong()) {
            return AppResult.Success(DownloadOutcome.InsufficientStorage(requiredBytes, availableBytes))
        }

        // Ensure fresh token
        tokenProvider.prepareForPlayback()
        val token =
            tokenProvider.getToken() ?: run {
                logger.error { "No auth token available" }
                return AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "Not authenticated"))
            }

        val serverUrl =
            serverConfig.getServerUrl()?.value ?: run {
                return AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "No server configured"))
            }

        // Create download entries
        val now = Clock.System.now().toEpochMilliseconds()
        val entities =
            toDownload.map { file ->
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
        downloadDao.insertAll(entities)

        // Download files concurrently in background
        for (file in toDownload) {
            scope.launch {
                // Refresh token per file to avoid 401 on long-running batches
                tokenProvider.prepareForPlayback()
                val fileToken = tokenProvider.getToken() ?: token
                downloadFile(
                    bookId = bookId.value,
                    audioFile = file,
                    serverUrl = serverUrl,
                    token = fileToken,
                )
            }
        }

        logger.info { "Queued ${toDownload.size} files for download: ${bookId.value}" }
        return AppResult.Success(DownloadOutcome.Started)
    }

    /**
     * Download a single file using NSURLSession download task.
     * Suspends until the download completes or fails.
     */
    private suspend fun downloadFile(
        bookId: String,
        audioFile: AudioFileResponse,
        serverUrl: String,
        token: String,
    ) = withContext(IODispatcher) {
        val audioFileId = audioFile.id
        val filename = audioFile.filename

        downloadDao.updateState(audioFileId, DownloadState.DOWNLOADING, Clock.System.now().toEpochMilliseconds())

        // Build URL — use Authorization header (in-process session supports headers)
        val url = "$serverUrl/api/v1/books/$bookId/audio/$audioFileId"
        val nsUrl =
            NSURL.URLWithString(url) ?: run {
                downloadDao.updateError(audioFileId, "Invalid URL")
                return@withContext
            }

        val request = NSMutableURLRequest.requestWithURL(nsUrl)
        request.setValue("Bearer $token", forHTTPHeaderField = "Authorization")

        logger.info { "Downloading: $filename (${audioFile.size / 1_000_000}MB)" }

        // Register this download so delegate can track it
        val destPath = fileManager.getAudioFilePath(bookId, audioFileId, filename, isTemp = false)

        // Ensure parent directory exists
        val destUrl = NSURL.fileURLWithPath(destPath.toString())
        NSFileManager.defaultManager.createDirectoryAtURL(
            destUrl.URLByDeletingLastPathComponent!!,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )

        // Suspend until download completes
        val result =
            suspendCancellableCoroutine { continuation ->
                val task = urlSession.downloadTaskWithRequest(request)
                // Store metadata for delegate
                task.taskDescription = "$bookId|$audioFileId|$filename|$destPath"

                // Register continuation so delegate can resume it
                sessionDelegate.registerDownload(task.taskIdentifier, continuation, destPath.toString(), bookId)

                continuation.invokeOnCancellation { task.cancel() }
                task.resume()
            }

        if (result) {
            downloadDao.markCompleted(audioFileId, destPath.toString(), Clock.System.now().toEpochMilliseconds())
            logger.info { "Downloaded: $filename (${audioFile.size / 1_000_000}MB)" }
        } else {
            // Error already logged/stored by delegate
            logger.error { "Download failed: $filename" }
        }
    }

    override suspend fun cancelDownload(bookId: BookId) {
        logger.info { "Cancelling download for book: ${bookId.value}" }
        val downloads = downloadDao.getForBook(bookId.value)
        for (download in downloads) {
            if (download.state == DownloadState.DOWNLOADING || download.state == DownloadState.QUEUED) {
                downloadDao.updateError(download.audioFileId, "Cancelled by user")
            }
        }

        // Cancel any active NSURLSession download tasks for this book
        val cancelledCount = sessionDelegate.cancelTasksForBook(bookId.value)
        if (cancelledCount > 0) {
            logger.info { "Cancelled $cancelledCount active download task(s) for book: ${bookId.value}" }
        }

        // Also cancel via the URL session to ensure tasks are actually stopped
        urlSession.getTasksWithCompletionHandler { _, _, downloadTasks ->
            downloadTasks?.forEach { task ->
                @Suppress("UNCHECKED_CAST")
                val downloadTask = task as? NSURLSessionDownloadTask ?: return@forEach
                val desc = downloadTask.taskDescription ?: return@forEach
                if (desc.startsWith("${bookId.value}|")) {
                    downloadTask.cancel()
                    logger.debug { "Cancelled NSURLSession task: ${downloadTask.taskIdentifier}" }
                }
            }
        }
    }

    override suspend fun deleteDownload(bookId: BookId) {
        logger.info { "Deleting downloads for book: ${bookId.value}" }
        fileManager.deleteBookFiles(bookId.value)
        downloadDao.markDeletedForBook(bookId.value)
    }

    @Suppress("ReturnCount")
    override suspend fun resumeIncompleteDownloads() {
        val incomplete = downloadDao.getIncomplete()
        if (incomplete.isEmpty()) return

        logger.info { "Resuming ${incomplete.size} incomplete downloads" }

        tokenProvider.prepareForPlayback()
        val token =
            tokenProvider.getToken() ?: run {
                logger.warn { "No token available for resume" }
                return
            }
        val serverUrl =
            serverConfig.getServerUrl()?.value ?: run {
                logger.warn { "No server URL for resume" }
                return
            }

        for (download in incomplete) {
            if (download.state != DownloadState.QUEUED) {
                downloadDao.updateState(download.audioFileId, DownloadState.QUEUED)
            }

            scope.launch {
                tokenProvider.prepareForPlayback()
                val fileToken = tokenProvider.getToken() ?: token
                downloadFile(
                    bookId = download.bookId,
                    audioFile =
                        AudioFileResponse(
                            id = download.audioFileId,
                            filename = download.filename,
                            format = "",
                            codec = "",
                            duration = 0,
                            size = download.totalBytes,
                        ),
                    serverUrl = serverUrl,
                    token = fileToken,
                )
            }
        }

        logger.info { "Re-enqueued ${incomplete.size} incomplete downloads" }
    }

    override fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus> =
        downloadDao.observeForBook(bookId.value).map { entities ->
            aggregateBookDownloadStatus(bookId.value, entities)
        }

    override fun observeAllStatuses(): Flow<Map<String, BookDownloadStatus>> =
        downloadDao.observeAll().map { downloads ->
            downloads
                .groupBy { it.bookId }
                .mapValues { (bookId, files) -> aggregateBookDownloadStatus(bookId, files) }
        }

    // Inline copy of DownloadManager.aggregateStatus until Task 8 moves the canonical version
    // into DownloadRepository. iOS keeps its own copy through W10 (deferred carveout per W8 design).
    // Known parity gap with Android's aggregator: does NOT filter DownloadState.CANCELLED from
    // activeDownloads. This gap now reaches the interface via observeAllStatuses (W8 Phase E D11) —
    // when a cross-book consumer arrives before W10 closes this carveout, fix here first or it
    // will mis-report aggregate status on iOS.
    private fun aggregateBookDownloadStatus(
        bookId: String,
        entities: List<DownloadEntity>,
    ): BookDownloadStatus {
        if (entities.isEmpty()) {
            return BookDownloadStatus.NotDownloaded(bookId)
        }
        val activeDownloads = entities.filter { it.state != DownloadState.DELETED }
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
                        activeDownloads.firstOrNull { it.state == DownloadState.FAILED }?.errorMessage
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
                    completedFiles = completedFiles,
                    totalBytes = totalBytes,
                    downloadedBytes = downloadedBytes,
                )
            }
        }
    }
}

/**
 * NSURLSession delegate for download tasks.
 *
 * Handles progress updates and completion. Each download task has a registered
 * continuation that is resumed when the task finishes.
 */
private class DownloadSessionDelegate(
    private val downloadDao: DownloadDao,
    private val scope: CoroutineScope,
) : NSObject(),
    NSURLSessionDownloadDelegateProtocol {
    private data class PendingDownload(
        val continuation: CancellableContinuation<Boolean>,
        val destPath: String,
    )

    /** Lock protecting pendingDownloads and lastLoggedPct (accessed from coroutines + delegate queue) */
    private val lock = platform.Foundation.NSRecursiveLock()

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private val pendingDownloads = mutableMapOf<ULong, PendingDownload>()
    private val lastLoggedPct = mutableMapOf<ULong, Int>()

    /** Maps taskIdentifier to bookId for cancellation support */
    private val taskToBookId = mutableMapOf<ULong, String>()

    fun registerDownload(
        taskId: ULong,
        continuation: CancellableContinuation<Boolean>,
        destPath: String,
        bookId: String? = null,
    ) {
        withLock {
            pendingDownloads[taskId] = PendingDownload(continuation, destPath)
            if (bookId != null) {
                taskToBookId[taskId] = bookId
            }
        }
    }

    /**
     * Cancel all active download tasks for a given book.
     * Returns the number of tasks cancelled.
     */
    fun cancelTasksForBook(bookId: String): Int {
        val taskIds =
            withLock {
                taskToBookId.filterValues { it == bookId }.keys.toList()
            }
        return taskIds.size
    }

    private fun removePending(taskId: ULong): PendingDownload? =
        withLock {
            lastLoggedPct.remove(taskId)
            taskToBookId.remove(taskId)
            pendingDownloads.remove(taskId)
        }

    private fun safeResume(
        continuation: CancellableContinuation<Boolean>,
        value: Boolean,
    ) {
        if (continuation.isActive) {
            continuation.resume(value)
        }
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) {
        val taskId = downloadTask.taskIdentifier
        val pending = withLock { pendingDownloads[taskId] } ?: return
        val parts = downloadTask.taskDescription?.split("|") ?: return
        val audioFileId = parts.getOrNull(1) ?: return
        val filename = parts.getOrNull(2) ?: "unknown"

        // Check HTTP status
        val httpResponse = downloadTask.response as? NSHTTPURLResponse
        val statusCode = httpResponse?.statusCode ?: 0
        if (statusCode !in 200L..299L && statusCode != 0L) {
            logger.error { "Download HTTP $statusCode for $filename" }
            scope.launch { downloadDao.updateError(audioFileId, "HTTP error: $statusCode") }
            removePending(taskId)?.let { safeResume(it.continuation, false) }
            return
        }

        // Move temp file to destination (iOS deletes temp after this callback)
        val destUrl = NSURL.fileURLWithPath(pending.destPath)
        NSFileManager.defaultManager.removeItemAtURL(destUrl, error = null)
        val moved = NSFileManager.defaultManager.moveItemAtURL(didFinishDownloadingToURL, toURL = destUrl, error = null)

        if (!moved) {
            logger.error { "Failed to move downloaded file: $filename" }
            scope.launch { downloadDao.updateError(audioFileId, "Failed to save file") }
            removePending(taskId)?.let { safeResume(it.continuation, false) }
            return
        }

        // Verify file size
        val fileSize =
            NSFileManager.defaultManager
                .attributesOfItemAtPath(pending.destPath, error = null)
                ?.get(platform.Foundation.NSFileSize) as? Long ?: 0L
        if (fileSize == 0L) {
            logger.error { "Downloaded file is empty: $filename" }
            scope.launch { downloadDao.updateError(audioFileId, "Downloaded file is empty") }
            removePending(taskId)?.let { safeResume(it.continuation, false) }
            return
        }

        logger.info { "Download saved: $filename (${fileSize / 1_000_000}MB)" }
        removePending(taskId)?.let { safeResume(it.continuation, true) }
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) {
        val parts = downloadTask.taskDescription?.split("|") ?: return
        val audioFileId = parts.getOrNull(1) ?: return
        val filename = parts.getOrNull(2) ?: "unknown"
        val taskId = downloadTask.taskIdentifier

        // Throttle DB writes — every 1%
        if (totalBytesExpectedToWrite > 0) {
            val pct = (totalBytesWritten * 100 / totalBytesExpectedToWrite).toInt()
            val lastPct = withLock { lastLoggedPct[taskId] ?: -1 }
            if (pct >= lastPct + 1) {
                withLock { lastLoggedPct[taskId] = pct }
                scope.launch {
                    downloadDao.updateProgress(audioFileId, totalBytesWritten, totalBytesExpectedToWrite)
                }
                logger.info {
                    "Download $pct%: $filename (${totalBytesWritten / 1_000_000}/${totalBytesExpectedToWrite / 1_000_000}MB)"
                }
            }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        if (didCompleteWithError == null) return
        val taskId = task.taskIdentifier
        val pending = removePending(taskId) ?: return
        val parts = task.taskDescription?.split("|") ?: return
        val audioFileId = parts.getOrNull(1) ?: return

        logger.error { "Download error: ${didCompleteWithError.localizedDescription}" }
        scope.launch { downloadDao.updateError(audioFileId, didCompleteWithError.localizedDescription) }
        safeResume(pending.continuation, false)
    }
}

private fun AudioFileEntity.toAudioFileResponse(): AudioFileResponse =
    AudioFileResponse(
        id = id,
        filename = filename,
        format = format,
        codec = codec,
        duration = duration,
        size = size,
    )
