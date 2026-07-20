@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.repository.aggregateBookDownloadStatus
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.playback.AudioFileResponse
import com.calypsan.listenup.client.playback.AudioTokenProvider
import com.calypsan.listenup.client.playback.PlaybackBandwidthCoordinator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
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

/** Per-request timeout for download tasks, in seconds. */
private const val REQUEST_TIMEOUT_SECONDS = 60.0

/** Whole-resource timeout for a single download, in seconds (1 hour for large files). */
private const val RESOURCE_TIMEOUT_SECONDS = 3600.0

/** Storage headroom multiplier — require 10% more free space than the download needs. */
private const val STORAGE_HEADROOM_FACTOR = 1.1

/** Divisor to convert bytes into whole megabytes for log output. */
private const val BYTES_PER_MEGABYTE = 1_000_000

/**
 * iOS implementation of [DownloadService] using NSURLSession background downloads.
 *
 * **Direct-DAO carveout:** this class still writes directly to
 * [com.calypsan.listenup.client.data.local.db.DownloadDao] via the `downloadDao` constructor
 * parameter — a Sync Engine Rule 5 violation, not yet migrated. The Android side already injects
 * [com.calypsan.listenup.client.domain.repository.DownloadRepository]; the iOS migration onto
 * shared Kotlin VMs and shared repositories is still pending.
 *
 * This class's interface complies with [DownloadService] as follows:
 * - [downloadBook] returns [com.calypsan.listenup.api.result.AppResult]<[com.calypsan.listenup.client.domain.model.DownloadOutcome]>.
 * - [observeBookStatus] aggregates via the sealed [com.calypsan.listenup.client.domain.model.BookDownloadStatus] hierarchy.
 *
 * The internal DAO writes remain untouched.
 *
 * **Codec negotiation: intentionally absent on iOS.** Android's `DownloadWorker.resolveDownloadUrl`
 * negotiates with the server's `preparePlayback` endpoint to pick a transcoded variant when the
 * device doesn't support the source codec. iOS's native AVFoundation audio player supports every
 * codec the server can serve (AAC, MP3, FLAC, ALAC, Opus), so negotiation is unnecessary — the
 * download URL points directly at the original file. **If a future server adds a codec iOS does
 * not natively support, this class requires codec negotiation matching the Android worker's
 * pattern.** This is a principled asymmetry, not a silent omission.
 */
@OptIn(ExperimentalTime::class)
class AppleDownloadService internal constructor(
    private val downloadDao: DownloadDao,
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val serverConfig: ServerConfig,
    private val tokenProvider: AudioTokenProvider,
    private val fileManager: DownloadFileManager,
    private val prepareRepository: PlaybackPrepareRepository,
    private val downloadRepository: DownloadRepository,
    private val scope: CoroutineScope,
    private val playbackBandwidthCoordinator: PlaybackBandwidthCoordinator,
) : DownloadService {
    /**
     * Delegate handles download progress and completion.
     * Must be held as a strong reference (ObjC weak delegate pattern).
     */
    private val sessionDelegate = DownloadSessionDelegate(downloadDao, scope)

    init {
        // "Playback preempts downloads": while a stream is buffering, suspend in-flight download
        // tasks so the stream gets the bandwidth; resume them when playback is flowing again.
        scope.launch {
            playbackBandwidthCoordinator.shouldYield.collect { sessionDelegate.setYielding(it) }
        }
    }

    private val urlSession: NSURLSession =
        run {
            val config = NSURLSessionConfiguration.defaultSessionConfiguration
            config.timeoutIntervalForRequest = REQUEST_TIMEOUT_SECONDS
            config.timeoutIntervalForResource = RESOURCE_TIMEOUT_SECONDS
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
        if (availableBytes < (requiredBytes * STORAGE_HEADROOM_FACTOR).toLong()) {
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

        // Resolve the signed download URL via PlaybackService.prepare — the same server contract the
        // streaming path and Android use (GET /api/v1/audio/{bookId}/{fileId}?u=&exp=&sig=). The old
        // hardcoded /api/v1/books/{bookId}/audio/{fileId} route no longer exists and 404s. The signed
        // URL is RELATIVE, so prepend the server URL (NSURLSession has no base URL).
        val signedRelativeUrl =
            when (val resolved = resolveSignedDownloadUrl(bookId, audioFileId, prepareRepository)) {
                is AppResult.Success -> {
                    resolved.data
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to resolve download URL for $filename: ${resolved.error.message}" }
                    downloadDao.updateError(audioFileId, "Failed to resolve URL: ${resolved.error.message}")
                    return@withContext
                }
            }
        val url = serverUrl.trimEnd('/') + signedRelativeUrl
        val nsUrl =
            NSURL.URLWithString(url) ?: run {
                downloadDao.updateError(audioFileId, "Invalid URL")
                return@withContext
            }

        val request = NSMutableURLRequest.requestWithURL(nsUrl)
        request.setValue("Bearer $token", forHTTPHeaderField = "Authorization")

        logger.info { "Downloading: $filename (${audioFile.size / BYTES_PER_MEGABYTE}MB)" }

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
                continuation.invokeOnCancellation { task.cancel() }
                // registerDownload also STARTS the task (or leaves it suspended if playback is
                // currently yielding-bound) atomically under the delegate's lock — so a task can't
                // slip past a concurrent setYielding(true) and run at full bandwidth mid-buffer.
                sessionDelegate.registerDownload(task, continuation, destPath.toString(), bookId)
            }

        if (result) {
            downloadDao.markCompleted(audioFileId, destPath.toString(), Clock.System.now().toEpochMilliseconds())
            logger.info { "Downloaded: $filename (${audioFile.size / BYTES_PER_MEGABYTE}MB)" }
        } else {
            // Error already logged/stored by delegate
            logger.error { "Download failed: $filename" }
        }
    }

    override suspend fun cancelDownload(bookId: BookId) {
        logger.info { "Cancelling download for book: ${bookId.value}" }

        // Route through the shared repository (Rule 5 canonical path): non-terminal rows transition to
        // CANCELLED, NOT FAILED. FAILED rows are returned by getIncomplete(), so a user-cancelled
        // download used to silently restart on the next launch (and burn cellular); CANCELLED is
        // excluded from getIncomplete(), so cancel stays cancelled.
        downloadRepository
            .cancelForBook(bookId)
            .onFailure { logger.warn { "Failed to persist cancelled state: ${bookId.value}" } }

        // Cancel any active NSURLSession download tasks for this book
        val cancelledCount = sessionDelegate.cancelTasksForBook(bookId.value)
        if (cancelledCount > 0) {
            logger.info { "Cancelled $cancelledCount active download task(s) for book: ${bookId.value}" }
        }
    }

    override suspend fun deleteDownload(bookId: BookId) {
        logger.info { "Deleting downloads for book: ${bookId.value}" }
        fileManager.deleteBookFiles(bookId.value)
        downloadDao.markDeletedForBook(bookId.value)
    }

    override suspend fun deleteAllDownloads() {
        logger.info { "Deleting all downloads (files + records)" }
        // Stop any in-flight tasks first so their completion callbacks can't re-write a wiped row.
        sessionDelegate.cancelAllTasks()
        fileManager.deleteAllFiles()
        downloadDao.deleteAll()
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

    // Shared reducer (commonMain aggregateBookDownloadStatus) — one state machine across platforms.
    // Previously an iOS-local copy that didn't filter CANCELLED, so a cancelled partial mis-reported
    // as Completed. The shared reducer treats a cancelled partial as Paused (never a false Downloaded).
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
    private val lock = reentrantLock()

    private val pendingDownloads = mutableMapOf<ULong, PendingDownload>()
    private val lastLoggedPct = mutableMapOf<ULong, Int>()

    /** Maps taskIdentifier to bookId for cancellation support */
    private val taskToBookId = mutableMapOf<ULong, String>()

    /** Maps taskIdentifier to its live download task so cancellation can stop it directly */
    private val taskById = mutableMapOf<ULong, NSURLSessionDownloadTask>()

    /**
     * Register and START the task atomically w.r.t. [setYielding]. Under the lock we decide to
     * `resume()` (start) only if not currently yielding — otherwise the task stays in its initial
     * suspended state and [setYielding]`(false)` starts it later. Doing the start inside the lock
     * closes the race where a task registered-and-resumed *after* a concurrent `setYielding(true)`
     * would run at full bandwidth for the whole buffer window.
     */
    fun registerDownload(
        task: NSURLSessionDownloadTask,
        continuation: CancellableContinuation<Boolean>,
        destPath: String,
        bookId: String? = null,
    ) {
        lock.withLock {
            val taskId = task.taskIdentifier
            pendingDownloads[taskId] = PendingDownload(continuation, destPath)
            taskById[taskId] = task
            if (bookId != null) {
                taskToBookId[taskId] = bookId
            }
            if (!yielding) task.resume()
        }
    }

    /**
     * Cancel all active download tasks for a given book.
     *
     * Each [NSURLSessionDownloadTask.cancel] drives the task to `didCompleteWithError`
     * (NSURLErrorCancelled), which resumes the suspended continuation as failed and clears its
     * pending state exactly once. Returns the number of tasks actually cancelled.
     */
    fun cancelTasksForBook(bookId: String): Int {
        val tasks =
            lock.withLock {
                taskToBookId
                    .filterValues { it == bookId }
                    .keys
                    .mapNotNull { taskById[it] }
            }
        tasks.forEach { it.cancel() }
        return tasks.size
    }

    /**
     * Cancel every active download task (used by "Delete All Downloads"). Each [cancel] drives its
     * task to `didCompleteWithError`, which resumes and clears its pending state exactly once.
     */
    fun cancelAllTasks(): Int {
        val tasks = lock.withLock { taskById.values.toList() }
        tasks.forEach { it.cancel() }
        return tasks.size
    }

    /** True while playback is buffering a stream — new tasks start suspended (see [registerDownload]). */
    private var yielding = false

    /**
     * The "playback preempts downloads" yield. Uses `NSURLSessionTask.suspend`/`resume` — which
     * hold the connection + partial data (no re-download, unlike cancel) — across every in-flight
     * task. Done under [lock] so it can't interleave with [registerDownload]'s start decision (that
     * race would let a task escape the yield). Idempotent: suspending an already-suspended (or
     * not-yet-started) task, or resuming a completed/removed one, is a documented no-op.
     */
    fun setYielding(active: Boolean) {
        lock.withLock {
            yielding = active
            taskById.values.forEach { if (active) it.suspend() else it.resume() }
        }
    }

    private fun removePending(taskId: ULong): PendingDownload? =
        lock.withLock {
            lastLoggedPct.remove(taskId)
            taskToBookId.remove(taskId)
            taskById.remove(taskId)
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
        val pending = lock.withLock { pendingDownloads[taskId] } ?: return
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

        logger.info { "Download saved: $filename (${fileSize / BYTES_PER_MEGABYTE}MB)" }
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
            val lastPct = lock.withLock { lastLoggedPct[taskId] ?: -1 }
            if (pct >= lastPct + 1) {
                lock.withLock { lastLoggedPct[taskId] = pct }
                scope.launch {
                    downloadDao.updateProgress(audioFileId, totalBytesWritten, totalBytesExpectedToWrite)
                }
                logger.info {
                    "Download $pct%: $filename (${totalBytesWritten / BYTES_PER_MEGABYTE}/${totalBytesExpectedToWrite / BYTES_PER_MEGABYTE}MB)"
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
