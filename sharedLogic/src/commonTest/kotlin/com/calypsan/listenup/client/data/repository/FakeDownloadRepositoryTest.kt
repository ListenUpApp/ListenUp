package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeDownloadRepositoryTest {
    private fun entity(
        audioFileId: String,
        bookId: String = "book-1",
        state: DownloadState = DownloadState.QUEUED,
        totalBytes: Long = 1000L,
        downloadedBytes: Long = 0L,
    ) = DownloadEntity(
        audioFileId = audioFileId,
        bookId = bookId,
        filename = "$audioFileId.mp3",
        fileIndex = 0,
        state = state,
        localPath = null,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        queuedAt = 0L,
        startedAt = null,
        completedAt = null,
        errorMessage = null,
        retryCount = 0,
    )

    @Test
    fun `markDownloading transitions state and emits update`() =
        runTest {
            val fake = FakeDownloadRepository(initial = listOf(entity("file-1")))
            fake.observeForBook(BookId("book-1")).test {
                assertEquals(DownloadState.QUEUED, awaitItem().single().state)
                fake.markDownloading("file-1", startedAt = 100L)
                val downloading = awaitItem().single()
                assertEquals(DownloadState.DOWNLOADING, downloading.state)
                assertEquals(100L, downloading.startedAt)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `updateProgress updates byte counts`() =
        runTest {
            val fake = FakeDownloadRepository(initial = listOf(entity("file-1")))
            val result = fake.updateProgress("file-1", downloadedBytes = 250L, totalBytes = 1000L)
            assertIs<AppResult.Success<Unit>>(result)
            assertEquals(250L, fake.entities.single().downloadedBytes)
        }

    @Test
    fun `markCompleted sets state and downloadedBytes equals totalBytes`() =
        runTest {
            val fake = FakeDownloadRepository(initial = listOf(entity("file-1")))
            fake.markCompleted("file-1", localPath = "/tmp/file-1.mp3", completedAt = 500L)
            val completed = fake.entities.single()
            assertEquals(DownloadState.COMPLETED, completed.state)
            assertEquals("/tmp/file-1.mp3", completed.localPath)
            assertEquals(500L, completed.completedAt)
            assertEquals(completed.totalBytes, completed.downloadedBytes)
        }

    @Test
    fun `markCancelled writes CANCELLED state`() =
        runTest {
            val fake = FakeDownloadRepository(initial = listOf(entity("file-1", state = DownloadState.DOWNLOADING)))
            fake.markCancelled("file-1")
            assertEquals(DownloadState.CANCELLED, fake.entities.single().state)
        }

    @Test
    fun `markFailed sets state and error message`() =
        runTest {
            val fake = FakeDownloadRepository(initial = listOf(entity("file-1")))
            fake.markFailed("file-1", DownloadError.DownloadFailed(debugInfo = "test failure"))
            val failed = fake.entities.single()
            assertEquals(DownloadState.FAILED, failed.state)
            assertTrue(failed.errorMessage?.contains("Download") == true)
        }

    @Test
    fun `markWaitingForServer writes WAITING_FOR_SERVER state and persists transcodeJobId`() =
        runTest {
            val fake = FakeDownloadRepository(initial = listOf(entity("file-1")))
            fake.markWaitingForServer("file-1", transcodeJobId = "job-abc")
            val after = fake.entities.single()
            assertEquals(DownloadState.WAITING_FOR_SERVER, after.state)
            assertEquals("job-abc", after.transcodeJobId)
        }

    @Test
    fun `recheckWaitingForServer is a no-op in Phase B`() =
        runTest {
            val fake = FakeDownloadRepository()
            val result = fake.recheckWaitingForServer()
            assertIs<AppResult.Success<Unit>>(result)
        }

    @Test
    fun `enqueueForBook returns Started by default`() =
        runTest {
            val fake = FakeDownloadRepository()
            val result = fake.enqueueForBook(BookId("book-1"))
            assertIs<AppResult.Success<DownloadOutcome>>(result)
            assertEquals(DownloadOutcome.Started, result.data)
        }

    @Test
    fun `enqueueForBook respects injected failure`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    enqueueFailure = { _ ->
                        AppResult.Success(DownloadOutcome.InsufficientStorage(requiredBytes = 1000, availableBytes = 500))
                    },
                )
            val result = fake.enqueueForBook(BookId("book-1"))
            assertIs<AppResult.Success<DownloadOutcome>>(result)
            assertIs<DownloadOutcome.InsufficientStorage>(result.data)
        }

    @Test
    fun `aggregator returns NotDownloaded for empty book`() =
        runTest {
            val fake = FakeDownloadRepository()
            fake.observeBookStatus(BookId("book-1")).test {
                assertIs<BookDownloadStatus.NotDownloaded>(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `aggregator returns Completed when all files complete`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial =
                        listOf(
                            entity("file-1", state = DownloadState.COMPLETED, downloadedBytes = 1000L),
                            entity("file-2", state = DownloadState.COMPLETED, downloadedBytes = 1000L),
                        ),
                )
            fake.observeBookStatus(BookId("book-1")).test {
                val status = awaitItem()
                assertIs<BookDownloadStatus.Completed>(status)
                assertEquals(2000L, status.totalBytes)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `aggregator returns Failed when any file failed`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial =
                        listOf(
                            entity("file-1", state = DownloadState.COMPLETED),
                            entity("file-2", state = DownloadState.FAILED),
                        ),
                )
            fake.observeBookStatus(BookId("book-1")).test {
                val status = awaitItem()
                assertIs<BookDownloadStatus.Failed>(status)
                assertEquals(1, status.partiallyDownloadedFiles)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `aggregator returns InProgress for mixed downloading and queued`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial =
                        listOf(
                            entity("file-1", state = DownloadState.DOWNLOADING, downloadedBytes = 500L),
                            entity("file-2", state = DownloadState.QUEUED),
                        ),
                )
            fake.observeBookStatus(BookId("book-1")).test {
                val status = awaitItem()
                assertIs<BookDownloadStatus.InProgress>(status)
                assertEquals(1, status.downloadingFiles)
                assertEquals(2, status.totalFiles)
                assertEquals(500L, status.downloadedBytes)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `aggregator counts WAITING_FOR_SERVER files`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial =
                        listOf(
                            entity("file-1", state = DownloadState.WAITING_FOR_SERVER),
                            entity("file-2", state = DownloadState.WAITING_FOR_SERVER),
                            entity("file-3", state = DownloadState.QUEUED),
                        ),
                )
            fake.observeBookStatus(BookId("book-1")).test {
                val status = awaitItem()
                assertIs<BookDownloadStatus.InProgress>(status)
                assertEquals(2, status.waitingForServerFiles)
                assertEquals(0, status.downloadingFiles)
                assertEquals(3, status.totalFiles)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `aggregator returns Paused when all files paused`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial =
                        listOf(
                            entity("file-1", state = DownloadState.PAUSED, downloadedBytes = 100L),
                            entity("file-2", state = DownloadState.PAUSED, downloadedBytes = 200L),
                        ),
                )
            fake.observeBookStatus(BookId("book-1")).test {
                val status = awaitItem()
                assertIs<BookDownloadStatus.Paused>(status)
                assertEquals(2, status.pausedFiles)
                assertEquals(300L, status.downloadedBytes)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `getLocalPath returns null for non-completed file`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial = listOf(entity("file-1", state = DownloadState.DOWNLOADING)),
                )
            assertNull(fake.getLocalPath("file-1"))
        }

    @Test
    fun `getLocalPath returns path for completed file`() =
        runTest {
            val fake = FakeDownloadRepository()
            fake.seed(
                entity("file-1", state = DownloadState.COMPLETED).copy(localPath = "/tmp/file-1.mp3"),
            )
            assertEquals("/tmp/file-1.mp3", fake.getLocalPath("file-1"))
        }

    @Test
    fun `deleteForBook removes all entities for that book`() =
        runTest {
            val fake =
                FakeDownloadRepository(
                    initial =
                        listOf(
                            entity("file-1", bookId = "book-1"),
                            entity("file-2", bookId = "book-2"),
                        ),
                )
            fake.deleteForBook("book-1")
            assertEquals(1, fake.entities.size)
            assertEquals("book-2", fake.entities.single().bookId)
        }
}
