@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorBackgroundTaskCancelledReasonKey
import platform.Foundation.NSURLErrorCallIsActive
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorCancelledReasonBackgroundUpdatesDisabled
import platform.Foundation.NSURLErrorCancelledReasonInsufficientSystemResources
import platform.Foundation.NSURLErrorCancelledReasonUserForceQuitApplication
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDataNotAllowed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorInternationalRoamingOff
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionDownloadTaskResumeData
import platform.Foundation.NSURLSessionTask
import platform.darwin.NSObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

private const val HTTP_FORBIDDEN = 403L
private const val PERCENT = 100

/**
 * How long a background wake waits for the delegate's writes before letting iOS suspend the app. iOS
 * allows about thirty seconds; the margin is for the hop back to UIKit.
 */
internal val BACKGROUND_DRAIN_TIMEOUT: Duration = 25.seconds

/** Connectivity failures: a transfer that hit one has not failed, it is waiting for a network. */
private val CONNECTIVITY_ERRORS =
    setOf(
        NSURLErrorNotConnectedToInternet,
        NSURLErrorNetworkConnectionLost,
        NSURLErrorTimedOut,
        NSURLErrorCannotConnectToHost,
        NSURLErrorCannotFindHost,
        NSURLErrorDNSLookupFailed,
        NSURLErrorInternationalRoamingOff,
        NSURLErrorDataNotAllowed,
        NSURLErrorCallIsActive,
    )

/**
 * The delegate of both background download sessions, and the authority on how a transfer ended.
 *
 * Under a background session the transfer outlives the process: a file can finish while ListenUp is
 * suspended or not running at all, and the system relaunches the app to deliver the callback. Nothing
 * waits on a transfer, so every outcome is written here, from the task's tag alone
 * ([DownloadTaskTag]), through [repository]:
 * - finished: the file is moved to its recomputed destination before this returns (the system
 *   deletes the temporary file when the callback ends), then the row is completed. A 403 means the
 *   signed URL expired, and the row goes back to the queue for a fresh one without spending a retry.
 * - first bytes: the row becomes DOWNLOADING (it is QUEUED until then, which is what makes "Waiting
 *   for Wi-Fi" read true while a task waits for an allowed network).
 * - ended with an error: resume data, when the system offers it, is stored. A swipe-away re-queues
 *   the row as [DownloadPauseReason.APP_CLOSED]; a system stop or a lost network pauses it, to be
 *   resumed by the next reconcile; only a genuine failure spends a retry. A cancellation this process
 *   asked for ([expectCancellation]) writes nothing: whoever asked has already recorded why.
 *
 * Every write is tracked, so [awaitEventsDelivered] can hold a background wake open until the
 * database says what the files on disk say. Callbacks arrive on the sessions' delegate queue, so the
 * mutable state is guarded by [lock].
 */
internal class DownloadSessionDelegate(
    private val repository: DownloadRepository,
    private val resumeData: ResumeDataStore,
    private val identifiers: DownloadSessionIdentifiers,
    private val destinationFor: (DownloadTaskTag) -> String,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val sessionIdentifierOf: (NSURLSession) -> String? = { it.configuration.identifier },
) : NSObject(),
    NSURLSessionDownloadDelegateProtocol {
    private val lock = reentrantLock()
    private val lastRecordedPercent = mutableMapOf<TaskKey, Int>()
    private val transferring = mutableSetOf<TaskKey>()
    private val expectedCancellations = mutableSetOf<TaskKey>()
    private val pendingWrites = mutableSetOf<Job>()
    private val drains = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** The next cancellation of [key] is this process's own (cancel, pause, delete, a move between sessions). */
    fun expectCancellation(key: TaskKey) {
        lock.withLock { expectedCancellations += key }
    }

    /**
     * Suspends until the session [identifier] has delivered every event of a background wake and the
     * writes they caused have landed, or [BACKGROUND_DRAIN_TIMEOUT] passes. Returns at once for an
     * identifier that is not ours.
     */
    suspend fun awaitEventsDelivered(identifier: String) {
        if (identifiers.networkOf(identifier) == null) {
            logger.warn { "download: drain for a session that is not ours id=$identifier" }
            return
        }
        val delivered = lock.withLock { drains.getOrPut(identifier) { CompletableDeferred() } }
        val finished = withTimeoutOrNull(BACKGROUND_DRAIN_TIMEOUT) { delivered.await() } != null
        lock.withLock { if (drains[identifier] === delivered) drains.remove(identifier) }
        if (!finished) logger.warn { "download: drain timed out id=$identifier" }
    }

    /** Suspends until every write a callback has started so far has landed. */
    suspend fun awaitWrites() {
        lock.withLock { pendingWrites.toList() }.joinAll()
    }

    override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
        val identifier = sessionIdentifierOf(session) ?: return
        scope.launch {
            awaitWrites()
            logger.info { "download: background events delivered id=$identifier" }
            lock.withLock { drains.getOrPut(identifier) { CompletableDeferred() } }.complete(Unit)
        }
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) {
        val tag = DownloadTaskTag.parse(downloadTask.taskDescription)
        if (tag == null) {
            logger.error { "download: finished task carries no tag of ours task=${downloadTask.taskIdentifier}" }
            return
        }
        finished(tag, (downloadTask.response as? NSHTTPURLResponse)?.statusCode ?: 0L, didFinishDownloadingToURL)
    }

    /** The outcome of a finished transfer of [tag]'s file, answered with HTTP [status]. */
    internal fun finished(
        tag: DownloadTaskTag,
        status: Long,
        temporaryFile: NSURL,
    ) {
        when {
            status == HTTP_FORBIDDEN -> {
                logger.warn { "download: url expired file=${tag.audioFileId}" }
                trackedWrite { repository.markQueued(tag.audioFileId, DownloadPauseReason.URL_EXPIRED) }
            }

            status != 0L && status !in 200L..299L -> {
                logger.error { "download: http error file=${tag.audioFileId} status=$status" }
                trackedWrite {
                    repository.markFailed(
                        tag.audioFileId,
                        DownloadError.DownloadFailed(debugInfo = "HTTP $status"),
                    )
                }
            }

            else -> {
                fileArrived(tag, temporaryFile)
            }
        }
    }

    /** Moves the finished file into place now, while it still exists, and records it. */
    private fun fileArrived(
        tag: DownloadTaskTag,
        temporaryFile: NSURL,
    ) {
        val destination = destinationFor(tag)
        val fileManager = NSFileManager.defaultManager
        val destinationUrl = NSURL.fileURLWithPath(destination)
        destinationUrl.URLByDeletingLastPathComponent?.let {
            fileManager.createDirectoryAtURL(it, withIntermediateDirectories = true, attributes = null, error = null)
        }
        fileManager.removeItemAtURL(destinationUrl, error = null)
        if (!fileManager.moveItemAtURL(temporaryFile, toURL = destinationUrl, error = null)) {
            // A second delivery of the same finish finds the temporary file already gone; the first
            // one recorded it, and the guarded writes below make a repeat harmless.
            logger.error { "download: could not move finished file file=${tag.audioFileId}" }
            trackedWrite {
                repository.markFailed(
                    tag.audioFileId,
                    DownloadError.DownloadFailed(debugInfo = "move failed"),
                )
            }
            return
        }
        val size = fileManager.attributesOfItemAtPath(destination, error = null)?.get(NSFileSize) as? Long ?: 0L
        if (size == 0L) {
            logger.error { "download: finished file is empty file=${tag.audioFileId}" }
            fileManager.removeItemAtPath(destination, error = null)
            trackedWrite {
                repository.markFailed(
                    tag.audioFileId,
                    DownloadError.DownloadFailed(debugInfo = "empty file"),
                )
            }
            return
        }
        resumeData.delete(tag.audioFileId)
        trackedWrite {
            val recorded =
                repository.markCompleted(
                    tag.audioFileId,
                    storedDownloadPath(destination),
                    clock.now().toEpochMilliseconds(),
                )
            if (recorded is AppResult.Success && !recorded.data) {
                // Cancelled or deleted while the last bytes landed: nothing points at the file any more.
                NSFileManager.defaultManager.removeItemAtPath(destination, error = null)
                logger.info {
                    "download: discarded a file that finished after it was cancelled file=${tag.audioFileId}"
                }
            } else {
                logger.info { "download: completed file=${tag.audioFileId} bytes=$size" }
            }
            recorded
        }
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) {
        val tag = DownloadTaskTag.parse(downloadTask.taskDescription) ?: return
        val key = keyOf(session, downloadTask) ?: return
        if (lock.withLock { transferring.add(key) }) {
            trackedWrite { repository.markTransferring(tag.audioFileId, clock.now().toEpochMilliseconds()) }
        }
        if (totalBytesExpectedToWrite <= 0) return
        val percent = (totalBytesWritten * PERCENT / totalBytesExpectedToWrite).toInt()
        val due =
            lock.withLock {
                val last = lastRecordedPercent[key] ?: -1
                (percent > last).also { if (it) lastRecordedPercent[key] = percent }
            }
        if (due) {
            trackedWrite { repository.updateProgress(tag.audioFileId, totalBytesWritten, totalBytesExpectedToWrite) }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didResumeAtOffset: Long,
        expectedTotalBytes: Long,
    ) {
        val tag = DownloadTaskTag.parse(downloadTask.taskDescription) ?: return
        // The proof a resume worked: an offset of 0 means the server or the system started over.
        logger.info {
            "download: resumed file=${tag.audioFileId} offset=$didResumeAtOffset expected=$expectedTotalBytes"
        }
        if (expectedTotalBytes > 0) {
            trackedWrite { repository.updateProgress(tag.audioFileId, didResumeAtOffset, expectedTotalBytes) }
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: NSError?,
    ) {
        val key = keyOf(session, task)
        val expected =
            lock.withLock {
                key?.let {
                    lastRecordedPercent.remove(it)
                    transferring.remove(it)
                    expectedCancellations.remove(it)
                } ?: false
            }
        val error = didCompleteWithError ?: return
        val tag = DownloadTaskTag.parse(task.taskDescription)
        val blob = error.userInfo[NSURLSessionDownloadTaskResumeData] as? NSData
        val cancelled = error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled
        val cancelReason = error.userInfo[NSURLErrorBackgroundTaskCancelledReasonKey] as? Long
        logger.info {
            "download: task ended file=${tag?.audioFileId} session=${key?.network} domain=${error.domain} " +
                "code=${error.code} cancelReason=$cancelReason resumeBytes=${blob?.length ?: 0} " +
                "written=${task.countOfBytesReceived} expected=${task.countOfBytesExpectedToReceive}"
        }
        if (tag == null) return
        when {
            // This process stopped it, and recorded why when it did.
            cancelled && expected -> {
                Unit
            }

            cancelled && cancelReason == NSURLErrorCancelledReasonUserForceQuitApplication -> {
                keep(tag, task, blob)
                trackedWrite { repository.markQueued(tag.audioFileId, DownloadPauseReason.APP_CLOSED) }
            }

            cancelled &&
                (
                    cancelReason == NSURLErrorCancelledReasonBackgroundUpdatesDisabled ||
                        cancelReason == NSURLErrorCancelledReasonInsufficientSystemResources
                ) -> {
                keep(tag, task, blob)
                trackedWrite { repository.markPaused(tag.audioFileId, DownloadPauseReason.INTERRUPTED) }
            }

            // A cancellation with no reason that this process did not ask for was asked for by an earlier
            // one, which recorded its own outcome before it ended.
            cancelled -> {
                Unit
            }

            error.domain == NSURLErrorDomain && error.code in CONNECTIVITY_ERRORS -> {
                keep(tag, task, blob)
                trackedWrite { repository.markPaused(tag.audioFileId, DownloadPauseReason.NETWORK_LOST) }
            }

            else -> {
                keep(tag, task, blob)
                trackedWrite {
                    repository.markFailed(
                        tag.audioFileId,
                        DownloadError.DownloadFailed(debugInfo = "${error.domain} ${error.code}"),
                    )
                }
            }
        }
    }

    /** Keeps [blob], so the next attempt continues from the byte this one reached. */
    private fun keep(
        tag: DownloadTaskTag,
        task: NSURLSessionTask,
        blob: NSData?,
    ) {
        if (blob == null) return
        val stored = resumeData.save(tag.audioFileId, blob, signedUrlExpiry(task.originalRequest?.URL?.absoluteString))
        if (!stored) logger.warn { "download: could not store resume data file=${tag.audioFileId}" }
    }

    private fun keyOf(
        session: NSURLSession,
        task: NSURLSessionTask,
    ): TaskKey? = identifiers.networkOf(sessionIdentifierOf(session))?.let { TaskKey(it, task.taskIdentifier) }

    /** Launches [write] and tracks it until it lands, so a background wake can wait for it. */
    private fun trackedWrite(write: suspend () -> AppResult<*>) {
        // Lazy, so the job is tracked before it can run and finish.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                write().onFailure { logger.warn { "download: write failed ${it.debugInfo ?: it.message}" } }
            }
        lock.withLock { pendingWrites += job }
        job.invokeOnCompletion { lock.withLock { pendingWrites -= job } }
        job.start()
    }
}
