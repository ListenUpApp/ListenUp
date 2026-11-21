@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorBackgroundTaskCancelledReasonKey
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorCancelledReasonUserForceQuitApplication
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionDownloadTaskResumeData
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding

private val identifiers = DownloadSessionIdentifiers.forBundle("test.listenup")

/**
 * The background-session delegate, which decides how every transfer ended.
 *
 * Nothing waits on a background transfer: it can finish while the app is suspended or not running,
 * and the system relaunches the app to say so. So each outcome is written from the task's tag
 * alone, with no registration from the process that started it — which is what these specs drive.
 */
class DownloadSessionDelegateTest :
    FunSpec({
        test("a file that finishes after a relaunch is moved to its recomputed place and recorded") {
            runTest {
                val rig = Rig(this, row(DownloadState.DOWNLOADING))
                val finished = rig.temporaryFile("audio-bytes")

                rig.delegate.URLSession(NSURLSession.sharedSession, rig.task(), didFinishDownloadingToURL = finished)
                runCurrent()

                val stored = rig.repository.row()
                stored.state shouldBe DownloadState.COMPLETED
                stored.localPath shouldBe "audiobooks/b1/f1.m4b"
                NSFileManager.defaultManager.fileExistsAtPath(rig.destination) shouldBe true
            }
        }

        test("an expired URL puts the file back in the queue for a fresh one, spending no retry") {
            runTest {
                val rig = Rig(this, row(DownloadState.DOWNLOADING))

                rig.delegate.finished(rig.tag, status = 403, temporaryFile = rig.temporaryFile("Forbidden"))
                runCurrent()

                val stored = rig.repository.row()
                stored.state shouldBe DownloadState.QUEUED
                stored.pauseReason shouldBe DownloadPauseReason.URL_EXPIRED
                stored.retryCount shouldBe 0
                // The error body is not the audio: nothing lands where the file belongs.
                NSFileManager.defaultManager.fileExistsAtPath(rig.destination) shouldBe false
            }
        }

        test("a file that finishes after it was cancelled is thrown away, and the row stays cancelled") {
            runTest {
                val rig = Rig(this, row(DownloadState.CANCELLED))

                rig.delegate.URLSession(NSURLSession.sharedSession, rig.task(), didFinishDownloadingToURL = rig.temporaryFile("x"))
                runCurrent()

                rig.repository.row().state shouldBe DownloadState.CANCELLED
                NSFileManager.defaultManager.fileExistsAtPath(rig.destination) shouldBe false
            }
        }

        test("a lost connection keeps the resume data and pauses the file instead of failing it") {
            runTest {
                val rig = Rig(this, row(DownloadState.DOWNLOADING))
                val error = urlError(NSURLErrorNetworkConnectionLost, resumeData = bytes("partial"))

                rig.delegate.URLSession(NSURLSession.sharedSession, task = rig.task(), didCompleteWithError = error)
                runCurrent()

                val stored = rig.repository.row()
                stored.state shouldBe DownloadState.PAUSED
                stored.pauseReason shouldBe DownloadPauseReason.NETWORK_LOST
                stored.retryCount shouldBe 0
                rig.resumeData
                    .load("f1")
                    .shouldNotBeNull()
                    .data shouldBe bytes("partial")
            }
        }

        test("a swipe-away puts the file back in the queue to resume, and says why") {
            runTest {
                val rig = Rig(this, row(DownloadState.DOWNLOADING))
                val error =
                    urlError(
                        NSURLErrorCancelled,
                        resumeData = bytes("partial"),
                        cancelReason = NSURLErrorCancelledReasonUserForceQuitApplication,
                    )

                rig.delegate.URLSession(NSURLSession.sharedSession, task = rig.task(), didCompleteWithError = error)
                runCurrent()

                val stored = rig.repository.row()
                stored.state shouldBe DownloadState.QUEUED
                stored.pauseReason shouldBe DownloadPauseReason.APP_CLOSED
                rig.resumeData.load("f1").shouldNotBeNull()
            }
        }

        test("a cancellation this process asked for records nothing") {
            runTest {
                val rig = Rig(this, row(DownloadState.PAUSED, DownloadPauseReason.PAUSED_BY_YOU))
                val task = rig.task()
                rig.delegate.expectCancellation(TaskKey(DownloadNetwork.Any, task.taskIdentifier))

                rig.delegate.URLSession(NSURLSession.sharedSession, task = task, didCompleteWithError = urlError(NSURLErrorCancelled))
                runCurrent()

                rig.repository.row() shouldBe row(DownloadState.PAUSED, DownloadPauseReason.PAUSED_BY_YOU)
                rig.resumeData.load("f1").shouldBeNull()
            }
        }

        test("any other error is a failure that spends a retry") {
            runTest {
                val rig = Rig(this, row(DownloadState.DOWNLOADING))

                rig.delegate.URLSession(
                    NSURLSession.sharedSession,
                    task = rig.task(),
                    didCompleteWithError = NSError.errorWithDomain("test", 1, null),
                )
                runCurrent()

                val stored = rig.repository.row()
                stored.state shouldBe DownloadState.FAILED
                stored.retryCount shouldBe 1
            }
        }

        test("the first bytes make a queued file downloading, once") {
            runTest {
                val rig = Rig(this, row(DownloadState.QUEUED, DownloadPauseReason.APP_CLOSED))
                val task = rig.task()

                rig.delegate.URLSession(
                    NSURLSession.sharedSession,
                    task,
                    didWriteData = 10,
                    totalBytesWritten = 10,
                    totalBytesExpectedToWrite = 100,
                )
                rig.delegate.URLSession(
                    NSURLSession.sharedSession,
                    task,
                    didWriteData = 10,
                    totalBytesWritten = 20,
                    totalBytesExpectedToWrite = 100,
                )
                runCurrent()

                val stored = rig.repository.row()
                stored.state shouldBe DownloadState.DOWNLOADING
                stored.pauseReason.shouldBeNull()
                stored.downloadedBytes shouldBe 20
                rig.repository.transferringCalls shouldBe 1
            }
        }

        test("a background wake is not let go until the writes its events caused have landed") {
            runTest {
                val rig = Rig(this, row(DownloadState.QUEUED))
                rig.repository.holdWrites()
                val drained = async { rig.delegate.awaitEventsDelivered(identifiers.anyNetwork) }
                runCurrent()

                rig.delegate.URLSession(
                    NSURLSession.sharedSession,
                    rig.task(),
                    didWriteData = 1,
                    totalBytesWritten = 1,
                    totalBytesExpectedToWrite = 0,
                )
                rig.delegate.URLSessionDidFinishEventsForBackgroundURLSession(NSURLSession.sharedSession)
                runCurrent()
                drained.isCompleted shouldBe false

                rig.repository.releaseWrites()
                runCurrent()
                drained.isCompleted shouldBe true
                rig.repository.row().state shouldBe DownloadState.DOWNLOADING
            }
        }

        test("a drain for a session that is not ours returns at once") {
            runTest {
                val rig = Rig(this, row(DownloadState.QUEUED))

                rig.delegate.awaitEventsDelivered("com.someone.else.downloads")
            }
        }
    })

/** One file, `f1` of book `b1`, a delegate over an in-memory repository, and scratch directories. */
private class Rig(
    scope: TestScope,
    initial: DownloadEntity,
) {
    private val root = "${NSTemporaryDirectory().trimEnd('/')}/delegate-${NSUUID().UUIDString}"
    val tag = DownloadTaskTag(bookId = "b1", audioFileId = "f1", filename = "f1.m4b")
    val destination = "$root/audiobooks/b1/f1.m4b"
    val repository = CountingRepository(initial)
    val resumeData = ResumeDataStore("$root/resume")
    val delegate =
        DownloadSessionDelegate(
            repository = repository,
            resumeData = resumeData,
            identifiers = identifiers,
            destinationFor = { destination },
            scope = scope,
            sessionIdentifierOf = { identifiers.anyNetwork },
        )

    /** A real task that is never started, so it never touches the network. */
    fun task(): NSURLSessionDownloadTask =
        NSURLSession
            .sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration)
            .downloadTaskWithURL(NSURL.URLWithString("https://example.test/a.m4b?exp=1&sig=x")!!)
            .also { it.taskDescription = tag.encode() }

    fun temporaryFile(contents: String): NSURL {
        NSFileManager.defaultManager.createDirectoryAtPath(root, withIntermediateDirectories = true, attributes = null, error = null)
        val path = "$root/${NSUUID().UUIDString}.tmp"
        NSFileManager.defaultManager.createFileAtPath(path, contents = bytes(contents), attributes = null)
        return NSURL.fileURLWithPath(path)
    }
}

/** The shared in-memory repository, counting the first-bytes writes and able to hold every write back. */
private class CountingRepository(
    initial: DownloadEntity,
) : FakeDownloadRepository(listOf(initial)) {
    var transferringCalls = 0
        private set
    private var gate: CompletableDeferred<Unit>? = null

    fun holdWrites() {
        gate = CompletableDeferred()
    }

    fun releaseWrites() {
        gate?.complete(Unit)
    }

    fun row(): DownloadEntity = entities.single()

    override suspend fun markTransferring(
        audioFileId: String,
        startedAt: Long,
    ): AppResult<Unit> {
        transferringCalls++
        gate?.await()
        return super.markTransferring(audioFileId, startedAt)
    }
}

private fun row(
    state: DownloadState,
    reason: DownloadPauseReason? = null,
): DownloadEntity =
    DownloadEntity(
        audioFileId = "f1",
        bookId = "b1",
        filename = "f1.m4b",
        fileIndex = 0,
        state = state,
        localPath = null,
        totalBytes = 100L,
        downloadedBytes = 0L,
        queuedAt = 0L,
        startedAt = null,
        completedAt = null,
        errorMessage = null,
        retryCount = 0,
        pauseReason = reason,
    )

private fun bytes(text: String): NSData = NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding)!!

private fun urlError(
    code: Long,
    resumeData: NSData? = null,
    cancelReason: Long? = null,
): NSError {
    val info = mutableMapOf<Any?, Any?>()
    resumeData?.let { info[NSURLSessionDownloadTaskResumeData] = it }
    cancelReason?.let { info[NSURLErrorBackgroundTaskCancelledReasonKey] = it }
    return NSError.errorWithDomain(NSURLErrorDomain, code, info)
}
