@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.client.data.local.db.AudioFileDao
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.local.db.heldRefusal
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.playback.PlaybackBandwidthCoordinator
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskPriorityLow
import kotlin.coroutines.resume
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

/** Storage headroom multiplier — require 10% more free space than the download needs. */
private const val STORAGE_HEADROOM_FACTOR = 1.1

/** A live task in one of the background sessions, and what reconciling knows of it. */
private class SessionTask(
    val task: NSURLSessionTask,
    val live: LiveDownloadTask,
)

/**
 * iOS implementation of [DownloadService], over two NSURLSession **background** sessions
 * ([BackgroundDownloadSessions]).
 *
 * A transfer belongs to the system, not to this process: it keeps going while ListenUp is suspended,
 * finishes even if iOS terminates the app (which is relaunched in the background to file it), and a
 * file the system interrupts resumes from resume data instead of from zero. So nothing here waits on
 * a transfer. Every file's task is created when the download starts; the sessions run three at a time;
 * [DownloadSessionDelegate] records how each ended. At launch and on every foreground, [reconcile]
 * compares the database with the transfers the system still holds ([reconcileDownloads]) and adopts,
 * stops, moves, resumes or re-requests each file.
 *
 * **Wi-Fi only** is a property of the session a task runs in; toggling it moves every transfer to the
 * other session, keeping its bytes ([reconcile] does the move).
 *
 * **Swiping ListenUp away** cancels every background transfer — an iOS rule. The cancellation is
 * delivered with resume data at the next launch, and the file resumes from where it stopped.
 *
 * **Playback yield.** While a stream buffers, live tasks are suspended and new ones created suspended;
 * every task also runs at low priority. Whether `suspend()` pauses a background-session task on a
 * device is undocumented; the low priority is what still holds when it does not.
 *
 * **Writes.** Transfer outcomes go through [DownloadRepository] (in the delegate). Enqueueing and
 * deleting insert or remove whole rows, which the domain-typed repository interface does not take, so
 * those stay on [DownloadDao] — the same split Android's `DownloadManager` makes.
 *
 * **Codec negotiation: intentionally absent on iOS.** AVFoundation plays every codec the server can
 * serve (AAC, MP3, FLAC, ALAC, Opus), so the download URL points directly at the original file. **If a
 * future server adds a codec iOS does not natively support, this class requires codec negotiation
 * matching the Android worker's pattern.** This is a principled asymmetry, not a silent omission.
 */
class AppleDownloadService internal constructor(
    private val downloadDao: DownloadDao,
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val serverConfig: ServerConfig,
    private val fileManager: DownloadFileManager,
    private val prepareRepository: PlaybackPrepareRepository,
    private val downloadRepository: DownloadRepository,
    private val scope: CoroutineScope,
    private val playbackBandwidthCoordinator: PlaybackBandwidthCoordinator,
    private val errorBus: ErrorBus,
    private val localPreferences: LocalPreferences,
    private val networkMonitor: NetworkMonitor,
    private val identifiers: DownloadSessionIdentifiers = DownloadSessionIdentifiers.current(),
    private val resumeData: ResumeDataStore = ResumeDataStore(appleResumeDataDirectory()),
) : DownloadService {
    override val supportsDownloads: Boolean = true

    private val delegate =
        DownloadSessionDelegate(
            repository = downloadRepository,
            resumeData = resumeData,
            identifiers = identifiers,
            destinationFor = { tag ->
                fileManager
                    .getAudioFilePath(
                        bookId = tag.bookId,
                        audioFileId = tag.audioFileId,
                        filename = tag.filename,
                        isTemp = false,
                    ).toString()
            },
            scope = scope,
        )

    /** Created on first use, so resolving this service (a Koin graph test does) builds no session. */
    private val sessions by lazy { BackgroundDownloadSessions(identifiers, delegate) }

    /** One change to the transfers at a time: enqueue, pause, cancel, delete and reconcile. */
    private val operations = Mutex()
    private val attached = atomic(false)
    private val yielding = atomic(false)

    /**
     * Creates both background sessions — which hands this process any transfers the system kept while
     * it was gone — starts following the preference, the network and playback, and reconciles. Called
     * from the app delegate at launch, before the system delivers the sessions' events; idempotent.
     */
    internal fun attach() {
        if (!attached.compareAndSet(expect = false, update = true)) return
        val created = sessions.all.size
        logger.info { "download: sessions attached count=$created ids=${identifiers.anyNetwork},${identifiers.unmetered}" }
        // "Playback preempts downloads": while a stream buffers, hold the transfers back.
        scope.launch { playbackBandwidthCoordinator.shouldYield.collect { setYielding(it) } }
        // A toggle of Wi-Fi only moves every transfer to the session it now selects.
        scope.launch { localPreferences.wifiOnlyDownloads.drop(1).collect { reconcile() } }
        // A network coming back picks up what a lost one paused.
        scope.launch { networkMonitor.onlineFlow.drop(1).filter { it }.collect { reconcile() } }
        scope.launch { reconcile() }
    }

    /**
     * Holds a background wake open until the session [identifier] has delivered its events and the
     * database records them. The app delegate calls the system's completion handler when this returns.
     */
    internal suspend fun drainBackgroundEvents(identifier: String) {
        attach()
        logger.info { "download: background events received id=$identifier" }
        delegate.awaitEventsDelivered(identifier)
    }

    override suspend fun getLocalPath(audioFileId: String): String? {
        val stored = downloadDao.getLocalPath(audioFileId) ?: return null
        val path = resolveDownloadPath(stored, appleDocumentsDirectory())
        if (fileManager.fileExists(path)) return path
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
        val documentsDir = appleDocumentsDirectory()
        return buildMap {
            for ((audioFileId, storedPath) in completed) {
                val path = resolveDownloadPath(storedPath, documentsDir)
                if (fileManager.fileExists(path)) {
                    put(audioFileId, path)
                } else {
                    logger.warn { "Downloaded file missing, cleaning up: $audioFileId" }
                    downloadDao.updateError(audioFileId, "File missing - deleted externally")
                }
            }
        }
    }

    override suspend fun wasExplicitlyDeleted(bookId: BookId): Boolean = downloadDao.hasDeletedRecords(bookId.value)

    @Suppress("ReturnCount")
    override suspend fun downloadBook(bookId: BookId): AppResult<DownloadOutcome> {
        attach()
        // A held book is triage-only (spec §8): it can't be downloaded until an admin releases it.
        bookDao.heldRefusal(bookId)?.let { refusal ->
            logger.info { "Refusing to download ${bookId.value}: held for review" }
            return AppResult.Failure(refusal)
        }
        val existing = downloadDao.getForBook(bookId.value)
        if (existing.isNotEmpty() && existing.all { it.state == DownloadState.COMPLETED }) {
            return AppResult.Success(DownloadOutcome.AlreadyDownloaded)
        }
        if (bookDao.getById(bookId) == null) {
            logger.error { "Book not found: ${bookId.value}" }
            return AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "Book not found"))
        }
        val audioFiles = audioFileDao.getForBook(bookId.value)
        if (audioFiles.isEmpty()) {
            return AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "No audio files available"))
        }
        // Files already complete or in flight are not started again.
        val activeIds =
            existing
                .filter { it.state in setOf(DownloadState.COMPLETED, DownloadState.DOWNLOADING, DownloadState.QUEUED) }
                .mapTo(mutableSetOf()) { it.audioFileId }
        val toDownload = audioFiles.filterNot { it.id in activeIds }
        if (toDownload.isEmpty()) {
            logger.info { "All files already downloading or completed for ${bookId.value}" }
            return AppResult.Success(DownloadOutcome.AlreadyDownloaded)
        }
        val requiredBytes = toDownload.sumOf { it.size }
        val availableBytes = fileManager.getAvailableSpace()
        if (availableBytes < (requiredBytes * STORAGE_HEADROOM_FACTOR).toLong()) {
            return AppResult.Success(DownloadOutcome.InsufficientStorage(requiredBytes, availableBytes))
        }
        val serverUrl =
            serverConfig.getServerUrl()?.value
                ?: return AppResult.Failure(DownloadError.DownloadFailed(debugInfo = "No server configured"))

        return operations.withLock {
            // ONE prepare() round-trip for the whole book — the response carries every file's URL.
            val signedUrls =
                when (val resolved = resolveSignedDownloadUrls(bookId.value, prepareRepository)) {
                    is AppResult.Success -> resolved.data
                    is AppResult.Failure -> return@withLock AppResult.Failure(resolved.error)
                }
            // A file that already has bytes keeps them, so its progress does not jump back to zero.
            val rows =
                queuedDownloadRows(
                    bookId = bookId.value,
                    audioFiles = audioFiles,
                    toDownload = toDownload,
                    existing = existing,
                    queuedAt = Clock.System.now().toEpochMilliseconds(),
                )
            downloadDao.insertAll(rows)
            val network = networkFor(localPreferences.wifiOnlyDownloads.value)
            val unstarted =
                rows.filterNot { row ->
                    startFromResumeData(row, network) ||
                        startFromUrl(
                            row = row,
                            signedRelativeUrl = signedUrls[row.audioFileId],
                            serverUrl = serverUrl,
                            network = network,
                        )
                }
            // A file the server did not sign cannot be fetched at all; it fails here, against its retry
            // budget, rather than being re-requested on every foreground forever.
            unstarted.forEach { downloadDao.updateError(it.audioFileId, "Server did not provide a download URL") }
            val started = rows.size - unstarted.size
            logger.info { "download: enqueued book=${bookId.value} files=${rows.size} started=$started session=$network" }
            AppResult.Success(DownloadOutcome.Started)
        }
    }

    /**
     * Pauses the book: each transfer is stopped with its resume data kept, and the rows are marked
     * paused by the person, so no launch resumes them until [downloadBook] does.
     */
    override suspend fun pauseDownload(bookId: BookId) {
        attach()
        operations.withLock {
            val stopped =
                tasksForBook(bookId.value).count { (task, live) ->
                    delegate.expectCancellation(live.key)
                    val tag = live.tag ?: return@count false
                    val data = (task as? NSURLSessionDownloadTask)?.cancelKeepingResumeData()
                    data != null &&
                        resumeData.save(tag.audioFileId, data, signedUrlExpiry(task.originalRequest?.URL?.absoluteString))
                }
            downloadDao
                .getForBook(bookId.value)
                .filter { it.state in setOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED) }
                .forEach { row ->
                    downloadRepository
                        .markPaused(row.audioFileId, DownloadPauseReason.PAUSED_BY_YOU)
                        .onFailure { logger.warn { "download: could not record pause file=${row.audioFileId}" } }
                }
            logger.info { "download: paused book=${bookId.value} resumable=$stopped" }
        }
    }

    override suspend fun cancelDownload(bookId: BookId) {
        attach()
        operations.withLock {
            // Rows first, so a transfer that finishes as it is cancelled finds them CANCELLED and is discarded.
            downloadRepository
                .cancelForBook(bookId)
                .onFailure { logger.warn { "Failed to persist cancelled state: ${bookId.value}" } }
            val cancelled = stopTransfers(bookId.value)
            logger.info { "download: cancelled book=${bookId.value} tasks=$cancelled" }
        }
    }

    /**
     * Honest-over-silent (audit finding): a partial/failed file deletion must NOT be recorded as
     * a completed delete — that would orphan bytes on disk with no DB row pointing at them and no
     * way for the user to reclaim the space. [DownloadFileManager.deleteBookFiles]'s boolean
     * result gates the DB write; on failure the row is left DOWNLOADED so the listener can retry.
     * Mirrors the Android `DownloadManager.deleteDownload` fix.
     */
    override suspend fun deleteDownload(bookId: BookId) {
        attach()
        operations.withLock {
            logger.info { "Deleting downloads for book: ${bookId.value}" }
            stopTransfers(bookId.value)
            if (!fileManager.deleteBookFiles(bookId.value)) {
                val bookTitle = bookDao.getById(bookId)?.title
                errorBus.emit(DownloadError.DeleteFailed(bookTitle = bookTitle))
                logger.error { "Failed to fully delete files for book: ${bookId.value}" }
                return@withLock
            }
            downloadDao.markDeletedForBook(bookId.value)
        }
    }

    override suspend fun deleteAllDownloads() {
        attach()
        operations.withLock {
            logger.info { "Deleting all downloads (files + records)" }
            liveTasks().forEach { (task, live) ->
                delegate.expectCancellation(live.key)
                task.cancel()
            }
            // A completion already in flight must not re-write a row after the wipe.
            delegate.awaitWrites()
            resumeData.deleteAll()
            fileManager.deleteAllFiles()
            downloadDao.deleteAll()
        }
    }

    /** Every foreground: reconcile the database with the transfers the system holds. */
    override suspend fun resumeIncompleteDownloads() {
        attach()
        reconcile()
    }

    override fun observeBookStatus(bookId: BookId): Flow<BookDownloadStatus> = downloadRepository.observeBookStatus(bookId)

    override fun observeAllStatuses(): Flow<Map<String, BookDownloadStatus>> = downloadRepository.observeAllStatuses()

    /** Compares the database with the system's transfers and acts on the difference ([reconcileDownloads]). */
    private suspend fun reconcile() {
        operations.withLock {
            val live = liveTasks()
            val network = networkFor(localPreferences.wifiOnlyDownloads.value)
            val rows = downloadDao.observeAll().first()
            val plan =
                reconcileDownloads(
                    rows = rows,
                    tasks = live.map { it.live },
                    blobs = resumeData.storedFiles(),
                    network = network,
                    nowEpochSeconds = Clock.System.now().epochSeconds,
                )
            plan.discardBlobs.forEach(resumeData::delete)
            val byKey = live.associateBy { it.live.key }
            plan.cancel.forEach { stale ->
                delegate.expectCancellation(stale.key)
                byKey[stale.key]?.task?.cancel()
            }
            val restart = plan.prepareByBook.mapValues { it.value.toMutableList() }.toMutableMap()
            val rowsById = rows.associateBy { it.audioFileId }
            val moved =
                plan.migrate.count { moving ->
                    val row = moving.tag?.let { rowsById[it.audioFileId] } ?: return@count false
                    delegate.expectCancellation(moving.key)
                    val data = (byKey[moving.key]?.task as? NSURLSessionDownloadTask)?.cancelKeepingResumeData()
                    // Back to QUEUED until bytes move in the new session: "Waiting for Wi-Fi" reads true.
                    requeue(row.audioFileId, null)
                    if (data != null && startTask(row, network, resume = data)) {
                        true
                    } else {
                        restart.getOrPut(row.bookId) { mutableListOf() } += row
                        false
                    }
                }
            val resumed = plan.resumeFromBlob.count { row -> startFromResumeData(row, network) }
            val restarted = restart.entries.sumOf { (bookId, rows) -> restartFromServer(bookId, rows, network) }
            logger.info {
                "download: reconciled live=${live.size} adopted=${plan.adopt.size} cancelled=${plan.cancel.size} " +
                    "moved=$moved resumed=$resumed restarted=$restarted session=$network"
            }
        }
    }

    /** Asks the server for fresh URLs for [rows] of [bookId] and starts each from zero. Returns how many started. */
    private suspend fun restartFromServer(
        bookId: String,
        rows: List<DownloadEntity>,
        network: DownloadNetwork,
    ): Int {
        val serverUrl = serverConfig.getServerUrl()?.value
        val signedUrls =
            when (val resolved = resolveSignedDownloadUrls(bookId, prepareRepository)) {
                is AppResult.Success -> resolved.data
                is AppResult.Failure -> null
            }
        if (serverUrl == null || signedUrls == null) {
            // Not a failure of the files: offline, signed out or locked. The next reconcile tries again.
            logger.warn { "download: could not re-request book=$bookId; left for the next reconcile" }
            return 0
        }
        return rows.count { row ->
            val started =
                startFromUrl(
                    row = row,
                    signedRelativeUrl = signedUrls[row.audioFileId],
                    serverUrl = serverUrl,
                    network = network,
                )
            if (started) requeue(row.audioFileId, row.pauseReason.keptWhileResuming())
            started
        }
    }

    /** Starts [row]'s file from its resume data, when there is any. Returns whether a transfer started. */
    private suspend fun startFromResumeData(
        row: DownloadEntity,
        network: DownloadNetwork,
    ): Boolean {
        val blob = resumeData.load(row.audioFileId) ?: return false
        // Consumed: a new interruption delivers new resume data.
        resumeData.delete(row.audioFileId)
        val started = startTask(row, network, resume = blob.data)
        if (started && row.state != DownloadState.QUEUED) {
            requeue(row.audioFileId, row.pauseReason.keptWhileResuming())
        }
        return started
    }

    /** Back in the queue with a live transfer behind it, waiting for [reason] (null for none). */
    private suspend fun requeue(
        audioFileId: String,
        reason: DownloadPauseReason?,
    ) {
        downloadRepository
            .markQueued(audioFileId, reason)
            .onFailure { logger.warn { "download: could not record queued file=$audioFileId" } }
    }

    /** Starts [row]'s file from [signedRelativeUrl]. Returns whether a transfer started. */
    private fun startFromUrl(
        row: DownloadEntity,
        signedRelativeUrl: String?,
        serverUrl: String,
        network: DownloadNetwork,
    ): Boolean {
        val url = signedRelativeUrl?.let { NSURL.URLWithString(serverUrl.trimEnd('/') + it) }
        if (url == null) {
            logger.error { "download: no usable URL for file=${row.audioFileId}" }
            return false
        }
        // No Authorization header: the audio route authorizes by the signed query alone, and a bearer
        // token handed to nsurlsessiond would outlive its use.
        val request = NSMutableURLRequest.requestWithURL(url)
        request.applyDownloadNetworkPolicy(wifiOnlyDownloads = network == DownloadNetwork.Unmetered)
        return startTask(row, network, request = request)
    }

    private fun startTask(
        row: DownloadEntity,
        network: DownloadNetwork,
        request: NSMutableURLRequest? = null,
        resume: NSData? = null,
    ): Boolean {
        val session = sessions.session(network)
        val task =
            when {
                resume != null -> session.downloadTaskWithResumeData(resume)
                request != null -> session.downloadTaskWithRequest(request)
                else -> return false
            }
        task.taskDescription = DownloadTaskTag(row.bookId, row.audioFileId, row.filename).encode()
        // Below the stream's AVPlayer traffic, even when the yield cannot act.
        task.priority = NSURLSessionTaskPriorityLow
        // Created suspended while playback is buffering; the end of the yield starts it.
        if (!yielding.value) task.resume()
        return true
    }

    /** Stops every transfer of [bookId] and throws away their resume data. Returns how many stopped. */
    private suspend fun stopTransfers(bookId: String): Int {
        val tasks = tasksForBook(bookId)
        tasks.forEach { (task, live) ->
            delegate.expectCancellation(live.key)
            task.cancel()
        }
        downloadDao.getForBook(bookId).forEach { resumeData.delete(it.audioFileId) }
        return tasks.size
    }

    private suspend fun tasksForBook(bookId: String): List<Pair<NSURLSessionTask, LiveDownloadTask>> =
        liveTasks().filter { it.live.tag?.bookId == bookId }.map { it.task to it.live }

    /** Every task both sessions hold right now. */
    private suspend fun liveTasks(): List<SessionTask> =
        sessions.all.flatMap { (network, session) ->
            suspendCancellableCoroutine { continuation ->
                session.getAllTasksWithCompletionHandler { tasks ->
                    continuation.resume(tasks.orEmpty().filterIsInstance<NSURLSessionTask>())
                }
            }.map { task ->
                SessionTask(task, LiveDownloadTask(TaskKey(network, task.taskIdentifier), DownloadTaskTag.parse(task.taskDescription)))
            }
        }

    private suspend fun setYielding(active: Boolean) {
        yielding.value = active
        if (!attached.value) return
        liveTasks().forEach { if (active) it.task.suspend() else it.task.resume() }
    }
}

/** Stops the transfer and hands back its resume data, or null when the system has none. */
private suspend fun NSURLSessionDownloadTask.cancelKeepingResumeData(): NSData? =
    suspendCancellableCoroutine { continuation ->
        cancelByProducingResumeData { data -> continuation.resume(data) }
    }

/** A swipe-away keeps saying so while the file resumes; any other wait is over once it is queued again. */
private fun DownloadPauseReason?.keptWhileResuming(): DownloadPauseReason? = takeIf { it == DownloadPauseReason.APP_CLOSED }
