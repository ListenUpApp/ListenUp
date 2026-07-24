package com.calypsan.listenup.client.download

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.local.images.StoragePaths
import com.calypsan.listenup.client.data.repository.FakeDownloadRepository
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import java.io.File

/**
 * Contract tests for [persistDownloadCancellation], the cancellation-cleanup seam extracted from
 * [DownloadWorker.doWork]'s catch block so it can be exercised without WorkManager.
 *
 * Uses hand-rolled fakes, not mokkery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadCancellationTest :
    FunSpec({

        fun entity(
            audioFileId: String,
            state: DownloadState,
        ) = DownloadEntity(
            audioFileId = audioFileId,
            bookId = "book-1",
            filename = "$audioFileId.mp3",
            fileIndex = 0,
            state = state,
            localPath = null,
            totalBytes = 1000L,
            downloadedBytes = 0L,
            queuedAt = 0L,
            startedAt = null,
            completedAt = null,
            errorMessage = null,
            retryCount = 0,
        )

        fun fileManagerFor(tmpRoot: File): DownloadFileManager =
            DownloadFileManager(
                storagePaths =
                    object : StoragePaths {
                        override val filesDir: Path = Path(tmpRoot.absolutePath)
                    },
            )

        fun tempDir(): File = File(System.getProperty("java.io.tmpdir"), "dwct-${System.nanoTime()}").apply { mkdirs() }

        // The cleanup runs inside an already-cancelled coroutine (the worker's cancellation catch).
        // NonCancellable lets the pause write complete even though the scope is being cancelled —
        // without it, markPaused aborts at its suspension point (modelled by the fake's ensureActive)
        // and the pause is silently lost.
        test("cancel cleanup persists PAUSED even when invoked from a cancelled coroutine") {
            runTest {
                val tmpRoot = tempDir()
                try {
                    val repo =
                        object : FakeDownloadRepository(
                            initial = listOf(entity("file-1", state = DownloadState.DOWNLOADING)),
                        ) {
                            override suspend fun markPaused(audioFileId: String): AppResult<Unit> {
                                // Model the real Room-backed write: it observes cancellation here.
                                currentCoroutineContext().ensureActive()
                                return super.markPaused(audioFileId)
                            }
                        }
                    val fileManager = fileManagerFor(tmpRoot)

                    val job =
                        launch {
                            try {
                                awaitCancellation()
                            } catch (e: CancellationException) {
                                persistDownloadCancellation(repo, fileManager, "file-1", "book-1", "file-1.mp3")
                                throw e
                            }
                        }
                    advanceUntilIdle()
                    job.cancelAndJoin()

                    repo.entities.single().state shouldBe DownloadState.PAUSED
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }

        // A network-paused download (state PAUSED, not CANCELLED) keeps its partial .tmp for
        // Range-resume; the cleanup must not delete it. Guards the extraction's temp-handling branch.
        test("cancel cleanup keeps the partial .tmp for a paused (non-cancelled) download") {
            runTest {
                val tmpRoot = tempDir()
                try {
                    val repo = FakeDownloadRepository(initial = listOf(entity("file-1", state = DownloadState.DOWNLOADING)))
                    val fileManager = fileManagerFor(tmpRoot)
                    val tempPath = fileManager.getAudioFilePath("book-1", "file-1", "file-1.mp3", isTemp = true)
                    SystemFileSystem.sink(tempPath).buffered().use { sink -> sink.write(ByteArray(128) { 0x41 }) }

                    persistDownloadCancellation(repo, fileManager, "file-1", "book-1", "file-1.mp3")

                    repo.entities.single().state shouldBe DownloadState.PAUSED
                    withClue("paused download must keep its .tmp for Range-resume") {
                        SystemFileSystem.exists(tempPath) shouldBe true
                    }
                } finally {
                    tmpRoot.deleteRecursively()
                }
            }
        }
    })
