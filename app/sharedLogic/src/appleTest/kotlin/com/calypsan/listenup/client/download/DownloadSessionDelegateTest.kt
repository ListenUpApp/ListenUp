@file:OptIn(ExperimentalForeignApi::class)

package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.data.local.db.DownloadDao
import com.calypsan.listenup.client.data.local.db.AudioFileLocalPath
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding

/**
 * Every exit path of the NSURLSession download delegate resumes its caller exactly once.
 *
 * `downloadFile` parks on a `suspendCancellableCoroutine` that only the delegate can resume. Both
 * callbacks used to `return` early on an unusable `taskDescription` without resuming — one of them
 * after already removing the pending entry, so nothing could ever resume it. The caller then
 * suspended forever and its row stayed DOWNLOADING: a permanent spinner with no download behind it.
 * Tests 2 and 3 are that regression; without the fix they hang until `runTest` gives up.
 */
class DownloadSessionDelegateTest :
    FunSpec({

        /**
         * A real download task that is never `resume()`d, so it never touches the network. The
         * session carries a null delegate — the delegate under test is driven by hand instead.
         */
        fun task(tag: String?): NSURLSessionDownloadTask {
            val session = NSURLSession.sessionWithConfiguration(downloadSessionConfiguration())
            val downloadTask = session.downloadTaskWithURL(NSURL.URLWithString("https://example.test/a.m4b")!!)
            downloadTask.taskDescription = tag
            return downloadTask
        }

        test("a completion error resumes the caller with false and records the failure") {
            runTest {
                val dao = FakeDownloadDao()
                val delegate = DownloadSessionDelegate(dao, backgroundScope)
                // Yielding keeps registerDownload from starting the task, so nothing goes online.
                delegate.setYielding(true)
                val downloadTask = task("book1|file1|a.m4b|/tmp/a.m4b")

                val awaiting =
                    async {
                        suspendCancellableCoroutine { cont ->
                            delegate.registerDownload(downloadTask, cont, "/tmp/a.m4b", "book1")
                        }
                    }
                runCurrent()

                delegate.URLSession(
                    session = NSURLSession.sharedSession,
                    task = downloadTask,
                    didCompleteWithError = NSError.errorWithDomain("test", 1, null),
                )

                awaiting.await() shouldBe false
                runCurrent()
                dao.errors shouldBe listOf("file1" to DownloadState.FAILED)
            }
        }

        test("a completion error with no usable tag still resumes the caller") {
            runTest {
                val dao = FakeDownloadDao()
                val delegate = DownloadSessionDelegate(dao, backgroundScope)
                delegate.setYielding(true)
                val downloadTask = task(null)

                val awaiting =
                    async {
                        suspendCancellableCoroutine { cont ->
                            delegate.registerDownload(downloadTask, cont, "/tmp/a.m4b", "book1")
                        }
                    }
                runCurrent()

                delegate.URLSession(
                    session = NSURLSession.sharedSession,
                    task = downloadTask,
                    didCompleteWithError = NSError.errorWithDomain("test", 1, null),
                )

                // There is no row to mark, but the caller must not be left suspended forever.
                awaiting.await() shouldBe false
            }
        }

        test("a finished download with no usable tag resumes the caller with false") {
            runTest {
                val dao = FakeDownloadDao()
                val delegate = DownloadSessionDelegate(dao, backgroundScope)
                delegate.setYielding(true)
                val downloadTask = task(null)

                val awaiting =
                    async {
                        suspendCancellableCoroutine { cont ->
                            delegate.registerDownload(downloadTask, cont, "/tmp/a.m4b", "book1")
                        }
                    }
                runCurrent()

                delegate.URLSession(
                    session = NSURLSession.sharedSession,
                    downloadTask = downloadTask,
                    didFinishDownloadingToURL = NSURL.fileURLWithPath("/tmp/does-not-exist.m4b"),
                )

                // Pre-fix this returned with the entry still pending, and didCompleteWithError(null)
                // short-circuits, so nothing else could ever resume it.
                awaiting.await() shouldBe false
            }
        }

        test("a nil completion error after a successful finish does not resume a second time") {
            runTest {
                val dao = FakeDownloadDao()
                val delegate = DownloadSessionDelegate(dao, backgroundScope)
                delegate.setYielding(true)

                val tempDir = NSTemporaryDirectory().trimEnd('/')
                val sourcePath = "$tempDir/listenup-delegate-src.m4b"
                val destPath = "$tempDir/listenup-delegate-dest.m4b"
                NSFileManager.defaultManager.removeItemAtPath(sourcePath, error = null)
                NSFileManager.defaultManager.createFileAtPath(
                    sourcePath,
                    contents = NSString.create(string = "audio-bytes").dataUsingEncoding(NSUTF8StringEncoding),
                    attributes = null,
                )

                val downloadTask = task("book1|file1|a.m4b|$destPath")
                val awaiting =
                    async {
                        suspendCancellableCoroutine { cont ->
                            delegate.registerDownload(downloadTask, cont, destPath, "book1")
                        }
                    }
                runCurrent()

                delegate.URLSession(
                    session = NSURLSession.sharedSession,
                    downloadTask = downloadTask,
                    didFinishDownloadingToURL = NSURL.fileURLWithPath(sourcePath),
                )
                // A second resume would throw IllegalStateException out of CancellableContinuation,
                // so this test passing at all IS the assertion that the nil error is a no-op.
                delegate.URLSession(
                    session = NSURLSession.sharedSession,
                    task = downloadTask,
                    didCompleteWithError = null,
                )

                awaiting.await() shouldBe true
                NSFileManager.defaultManager.removeItemAtPath(destPath, error = null)
            }
        }
    })

/**
 * In-memory [DownloadDao]. Only the members the delegate actually calls record anything;
 * `updateError` is left to its interface default so the recorded call is the real
 * [DownloadDao.updateErrorWithState] the default routes through.
 */
@Suppress("TooManyFunctions")
private class FakeDownloadDao : DownloadDao {
    val errors = mutableListOf<Pair<String, DownloadState>>()
    val states = mutableListOf<Pair<String, DownloadState>>()
    val progress = mutableListOf<Triple<String, Long, Long>>()

    override suspend fun updateErrorWithState(
        audioFileId: String,
        error: String,
        state: DownloadState,
    ) {
        errors += audioFileId to state
    }

    override suspend fun updateState(
        audioFileId: String,
        state: DownloadState,
        startedAt: Long?,
    ) {
        states += audioFileId to state
    }

    override suspend fun updateProgress(
        audioFileId: String,
        downloaded: Long,
        total: Long,
    ) {
        progress += Triple(audioFileId, downloaded, total)
    }

    override fun observeForBook(bookId: String): Flow<List<DownloadEntity>> = TODO("not used")

    override fun observeAll(): Flow<List<DownloadEntity>> = TODO("not used")

    override suspend fun getForBook(bookId: String): List<DownloadEntity> = TODO("not used")

    override suspend fun getByAudioFileId(audioFileId: String): DownloadEntity? = TODO("not used")

    override suspend fun getIncompleteWithin(maxRetries: Int): List<DownloadEntity> = TODO("not used")

    override suspend fun getLocalPaths(audioFileIds: List<String>): List<AudioFileLocalPath> = TODO("not used")

    override suspend fun getLocalPath(audioFileId: String): String? = TODO("not used")

    override suspend fun insert(download: DownloadEntity): Unit = TODO("not used")

    override suspend fun insertAll(downloads: List<DownloadEntity>): Unit = TODO("not used")

    override suspend fun markPausedIfNotTerminal(audioFileId: String): Unit = TODO("not used")

    override suspend fun updateStateForBookExcluding(
        bookId: String,
        newState: DownloadState,
        excludeState: DownloadState,
    ): Unit = TODO("not used")

    override suspend fun markCompletedWithState(
        audioFileId: String,
        localPath: String,
        completedAt: Long,
        state: DownloadState,
    ): Unit = TODO("not used")

    override suspend fun markDeletedForBook(bookId: String): Unit = TODO("not used")

    override suspend fun hasDeletedRecords(bookId: String): Boolean = TODO("not used")

    override suspend fun deleteForBook(bookId: String): Unit = TODO("not used")

    override suspend fun deleteDeletedRecordsForBook(bookId: String): Unit = TODO("not used")

    override suspend fun deleteAll(): Unit = TODO("not used")
}
