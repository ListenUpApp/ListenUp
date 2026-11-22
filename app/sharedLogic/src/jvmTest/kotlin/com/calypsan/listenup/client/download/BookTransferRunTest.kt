package com.calypsan.listenup.client.download

import app.cash.turbine.test
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.client.data.repository.BookTransferStore
import com.calypsan.listenup.client.data.repository.DownloadRepositoryImpl
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeBookRepository
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.File

/**
 * [BookTransferRun], the core both Android transports share, against a real database. These are the behaviours the
 * background rewrite exists for: files go in order, a stop keeps the bytes and says why without spending a retry, a
 * lost connection waits, a genuine failure spends one retry at a time, and nothing the person paused or cancelled is
 * ever started.
 */
class BookTransferRunTest :
    FunSpec({
        /** A downloader whose behaviour per file is scripted; a missing script completes the file. */
        class ScriptedDownloader(
            private val repository: () -> DownloadRepository,
        ) : AudioFileDownloader {
            val order = mutableListOf<String>()
            val scripts = mutableMapOf<String, suspend (setProgress: suspend (Long, Long) -> Unit) -> AppResult<Unit>>()

            override suspend fun download(
                audioFileId: String,
                bookId: String,
                filename: String,
                expectedSize: Long,
                isStopped: () -> Boolean,
                setProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
            ): AppResult<Unit> {
                order += audioFileId
                scripts[audioFileId]?.let { return it(setProgress) }
                val _ = repository().updateProgress(audioFileId, expectedSize, expectedSize)
                setProgress(expectedSize, expectedSize)
                val _ = repository().markCompleted(audioFileId, "/books/$bookId/$filename", completedAt = 1L)
                return AppResult.Success(Unit)
            }
        }

        class RecordingHost(
            private val stopCause: StopCause = StopCause(DownloadStopReason.QUOTA, code = 10),
        ) : BookTransferHost {
            override val transport = DownloadTransport.UIDT
            override val foreground = true
            val progress = mutableListOf<BookTransferProgress>()

            override suspend fun onProgress(progress: BookTransferProgress) {
                this.progress += progress
            }

            override fun platformStopCause(): StopCause = stopCause
        }

        class Rig(
            val db: ListenUpDatabase,
        ) {
            val dao = db.downloadDao()
            val repository = DownloadRepositoryImpl(dao, FakeBookRepository(), enqueuer = NoEnqueuer)
            val store = BookTransferStore(dao, db.downloadStopEventDao(), RoomTransactionRunner(db))
            val registry = BookTransferRegistry()
            val errorBus = ErrorBus()
            val downloader = ScriptedDownloader { repository }
            val root = File(System.getProperty("java.io.tmpdir"), "btr-${System.nanoTime()}").apply { mkdirs() }
            val fileManager =
                DownloadFileManager(
                    storagePaths =
                        object : StoragePaths {
                            override val filesDir: Path = Path(root.absolutePath)
                        },
                )
            val run =
                BookTransferRun(
                    store = store,
                    repository = repository,
                    fileManager = fileManager,
                    downloader = downloader,
                    registry = registry,
                    errorBus = errorBus,
                    deviceState = { DownloadDeviceSnapshot(sdkInt = 35, standbyBucket = 40, notificationsEnabled = true) },
                    now = { 5_000L },
                )

            suspend fun row(id: String) = dao.getByAudioFileId(id)!!
        }

        fun rows(vararg states: Pair<String, DownloadState>) =
            states.mapIndexed { index, (id, state) ->
                DownloadEntity(
                    audioFileId = id,
                    bookId = "b1",
                    filename = "$id.mp3",
                    fileIndex = index,
                    state = state,
                    localPath = null,
                    totalBytes = 100,
                    downloadedBytes = if (state == DownloadState.COMPLETED) 100 else 0,
                    queuedAt = 0,
                    startedAt = null,
                    completedAt = null,
                    errorMessage = null,
                )
            }

        suspend fun withRig(block: suspend Rig.() -> Unit) {
            val db = createInMemoryTestDatabase()
            try {
                Rig(db).block()
            } finally {
                db.close()
            }
        }

        test("files transfer one at a time in file order, skipping those already done or given up on") {
            runTest {
                withRig {
                    dao.insertAll(
                        rows(
                            "f0" to DownloadState.COMPLETED,
                            "f1" to DownloadState.QUEUED,
                            "f2" to DownloadState.CANCELLED,
                            "f3" to DownloadState.QUEUED,
                        ),
                    )
                    val host = RecordingHost()

                    run.run("b1", host) shouldBe BookTransferOutcome.COMPLETED

                    downloader.order shouldContainExactly listOf("f1", "f3")
                    row("f1").state shouldBe DownloadState.COMPLETED
                    row("f3").state shouldBe DownloadState.COMPLETED
                    row("f2").state shouldBe DownloadState.CANCELLED
                    registry.isRunning("b1") shouldBe false
                }
            }
        }

        test("progress is the whole book's: earlier files' bytes plus the current file's, and which file it is") {
            runTest {
                withRig {
                    dao.insertAll(rows("f0" to DownloadState.COMPLETED, "f1" to DownloadState.QUEUED))
                    val host = RecordingHost()

                    run.run("b1", host)

                    host.progress.first() shouldBe
                        BookTransferProgress("b1", downloadedBytes = 100, totalBytes = 200, fileNumber = 2, fileCount = 2, resolving = true)
                    host.progress.last() shouldBe
                        BookTransferProgress(
                            "b1",
                            downloadedBytes = 200,
                            totalBytes = 200,
                            fileNumber = 2,
                            fileCount = 2,
                            resolving = false,
                        )
                }
            }
        }

        test("a file the person paused, or one waiting for sign-in, is never started") {
            runTest {
                withRig {
                    dao.insertAll(rows("f0" to DownloadState.PAUSED, "f1" to DownloadState.PAUSED, "f2" to DownloadState.QUEUED))
                    dao.markPausedIfNotTerminal("f0", DownloadPauseReason.PAUSED_BY_YOU)
                    dao.markPausedIfNotTerminal("f1", DownloadPauseReason.SIGN_IN_REQUIRED)

                    run.run("b1", RecordingHost())

                    downloader.order shouldContainExactly listOf("f2")
                    row("f0").state shouldBe DownloadState.PAUSED
                    row("f1").state shouldBe DownloadState.PAUSED
                }
            }
        }

        test("a file already downloading elsewhere is not claimed twice") {
            runTest {
                withRig {
                    dao.insertAll(rows("f0" to DownloadState.DOWNLOADING, "f1" to DownloadState.QUEUED))

                    run.run("b1", RecordingHost())

                    downloader.order shouldContainExactly listOf("f1")
                }
            }
        }

        test("a stop mid-file keeps the bytes, pauses the file, records why, and spends no retry") {
            runTest {
                withRig {
                    dao.insertAll(rows("f0" to DownloadState.QUEUED))
                    val started = CompletableDeferred<Unit>()
                    downloader.scripts["f0"] = {
                        val _ = repository.updateProgress("f0", 40, 100)
                        started.complete(Unit)
                        awaitCancellation()
                    }

                    val job = launch { run.run("b1", RecordingHost(StopCause(DownloadStopReason.QUOTA, code = 10))) }
                    started.await()
                    job.cancelAndJoin()

                    val stopped = row("f0")
                    stopped.state shouldBe DownloadState.PAUSED
                    stopped.pauseReason shouldBe DownloadPauseReason.INTERRUPTED
                    stopped.downloadedBytes shouldBe 40
                    stopped.retryCount shouldBe 0
                    stopped.stopReason shouldBe DownloadStopReason.QUOTA
                    stopped.stopReasonCode shouldBe 10
                    stopped.stopCount shouldBe 1
                    stopped.transport shouldBe DownloadTransport.UIDT
                    val event = db.downloadStopEventDao().recentStopEvents(10).single()
                    event.reason shouldBe DownloadStopReason.QUOTA
                    event.bytesAtStop shouldBe 40
                    event.standbyBucket shouldBe 40
                    registry.isRunning("b1") shouldBe false
                }
            }
        }

        test("a pause the person asked for is recorded as theirs and stays theirs") {
            runTest {
                withRig {
                    dao.insertAll(rows("f0" to DownloadState.QUEUED, "f1" to DownloadState.QUEUED))
                    val started = CompletableDeferred<Unit>()
                    downloader.scripts["f0"] = {
                        started.complete(Unit)
                        awaitCancellation()
                    }

                    val job = launch { run.run("b1", RecordingHost(StopCause(DownloadStopReason.CANCELLED_BY_APP, code = 1))) }
                    started.await()
                    registry.expectStop("b1", DownloadStopReason.USER_PAUSED)
                    dao.pauseBookForUser("b1")
                    job.cancelAndJoin()

                    row("f0").pauseReason shouldBe DownloadPauseReason.PAUSED_BY_YOU
                    row("f1").pauseReason shouldBe DownloadPauseReason.PAUSED_BY_YOU
                    row("f0").stopReason shouldBe DownloadStopReason.USER_PAUSED
                    db
                        .downloadStopEventDao()
                        .recentStopEvents(10)
                        .single()
                        .reasonCode shouldBe 1
                }
            }
        }

        test("a connection lost mid-read waits for the network: paused, no retry spent, no error raised") {
            runTest {
                withRig {
                    dao.insertAll(rows("f0" to DownloadState.QUEUED))
                    downloader.scripts["f0"] = { AppResult.Failure(TransportError.NetworkUnavailable(debugInfo = "reset")) }

                    errorBus.errors.test {
                        run.run("b1", RecordingHost()) shouldBe BookTransferOutcome.RETRY_LATER
                        expectNoEvents()
                    }

                    val waiting = row("f0")
                    waiting.state shouldBe DownloadState.PAUSED
                    waiting.pauseReason shouldBe DownloadPauseReason.NETWORK_LOST
                    waiting.retryCount shouldBe 0
                    waiting.stopReason shouldBe DownloadStopReason.NETWORK_LOST
                }
            }
        }

        test("a full disk, told by its type, fails the file, tells the person, and is not retried") {
            runTest {
                withRig {
                    dao.insertAll(rows("f0" to DownloadState.QUEUED))
                    downloader.scripts["f0"] = {
                        AppResult.Failure(DownloadError.InsufficientStorage(debugInfo = "ENOSPC (No space left on device)"))
                    }

                    errorBus.errors.test {
                        run.run("b1", RecordingHost()) shouldBe BookTransferOutcome.GAVE_UP
                        awaitItem().shouldBeInstanceOf<DownloadError.InsufficientStorage>()
                    }

                    val failed = row("f0")
                    failed.state shouldBe DownloadState.FAILED
                    failed.lastErrorCode shouldBe DownloadError.InsufficientStorage().code
                }
            }
        }

        // A lost connection's debugInfo names the host, and a self-hosted box is plausibly called storage.lan.
        test("a lost connection to a server called storage.lan waits for the network and is not taken for a full disk") {
            runTest {
                withRig {
                    dao.insertAll(rows("f0" to DownloadState.QUEUED))
                    downloader.scripts["f0"] = {
                        AppResult.Failure(
                            TransportError.NetworkUnavailable(
                                debugInfo = "Failed to connect to storage.lan/192.168.1.10:8080",
                            ),
                        )
                    }

                    errorBus.errors.test {
                        run.run("b1", RecordingHost()) shouldBe BookTransferOutcome.RETRY_LATER
                        expectNoEvents()
                    }

                    val waiting = row("f0")
                    waiting.state shouldBe DownloadState.PAUSED
                    waiting.pauseReason shouldBe DownloadPauseReason.NETWORK_LOST
                    waiting.retryCount shouldBe 0
                }
            }
        }

        test("a server failure spends one retry per run, records why, and gives up when the budget is spent") {
            runTest {
                withRig {
                    dao.insertAll(rows("f0" to DownloadState.QUEUED))
                    val error: AppError = TransportError.Server5xx(statusCode = 503)
                    downloader.scripts["f0"] = { AppResult.Failure(error) }

                    run.run("b1", RecordingHost()) shouldBe BookTransferOutcome.RETRY_LATER
                    row("f0").retryCount shouldBe 1
                    row("f0").lastErrorCode shouldBe "TRANSPORT_SERVER_5XX"
                    run.run("b1", RecordingHost()) shouldBe BookTransferOutcome.RETRY_LATER
                    run.run("b1", RecordingHost()) shouldBe BookTransferOutcome.GAVE_UP
                    row("f0").state shouldBe DownloadState.FAILED
                    row("f0").retryCount shouldBe 3

                    // The budget is spent: a fourth run has nothing to transfer.
                    run.run("b1", RecordingHost()) shouldBe BookTransferOutcome.COMPLETED
                    downloader.order.size shouldBe 3
                }
            }
        }

        test("an ended session pauses until sign-in and stops the run without a retry") {
            runTest {
                withRig {
                    dao.insertAll(rows("f0" to DownloadState.QUEUED, "f1" to DownloadState.QUEUED))
                    downloader.scripts["f0"] = { AppResult.Failure(AuthError.SessionExpired()) }

                    run.run("b1", RecordingHost()) shouldBe BookTransferOutcome.GAVE_UP

                    row("f0").pauseReason shouldBe DownloadPauseReason.SIGN_IN_REQUIRED
                    row("f0").stopReason shouldBe DownloadStopReason.AUTH_EXPIRED
                    downloader.order shouldContainExactly listOf("f0")
                }
            }
        }

        test("a file queued while the run is transferring is picked up before the run exits") {
            runTest {
                withRig {
                    dao.insertAll(rows("f0" to DownloadState.QUEUED))
                    downloader.scripts["f0"] = {
                        dao.insertAll(rows("f0" to DownloadState.DOWNLOADING, "f1" to DownloadState.QUEUED).drop(1))
                        val _ = repository.markCompleted("f0", "/books/b1/f0.mp3", completedAt = 1L)
                        AppResult.Success(Unit)
                    }

                    run.run("b1", RecordingHost()) shouldBe BookTransferOutcome.COMPLETED

                    downloader.order shouldContainExactly listOf("f0", "f1")
                }
            }
        }

        test("a second book waits for the first to finish before it transfers anything") {
            runTest {
                withRig {
                    dao.insertAll(rows("f0" to DownloadState.QUEUED))
                    dao.insertAll(rows("g0" to DownloadState.QUEUED).map { it.copy(bookId = "b2") })
                    val releaseFirst = CompletableDeferred<Unit>()
                    val firstStarted = CompletableDeferred<Unit>()
                    downloader.scripts["f0"] = {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                        val _ = repository.markCompleted("f0", "/books/b1/f0.mp3", completedAt = 1L)
                        AppResult.Success(Unit)
                    }

                    val first = async { run.run("b1", RecordingHost()) }
                    val second = async { run.run("b2", RecordingHost()) }
                    firstStarted.await()
                    downloader.order shouldContainExactly listOf("f0")

                    releaseFirst.complete(Unit)
                    first.await()
                    second.await()
                    downloader.order shouldContainExactly listOf("f0", "g0")
                }
            }
        }

        test("a stop that is the cancel of the download itself still re-throws so the host ends") {
            runTest {
                withRig {
                    dao.insertAll(rows("f0" to DownloadState.QUEUED))
                    downloader.scripts["f0"] = { throw CancellationException("cancelled before it completed") }

                    shouldThrow<CancellationException> { run.run("b1", RecordingHost()) }
                    registry.isRunning("b1") shouldBe false
                }
            }
        }
    })

private object NoEnqueuer : DownloadEnqueuer {
    override suspend fun enqueue(entity: DownloadEntity): AppResult<Unit> = AppResult.Success(Unit)
}
