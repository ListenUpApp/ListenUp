package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.api.error.DownloadError
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadOutcome
import com.calypsan.listenup.client.domain.model.DownloadStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class FakeDownloadRepositoryTest :
    FunSpec({
        fun entity(
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

        test("markDownloading transitions state and emits update") {
            runTest {
                val fake = FakeDownloadRepository(initial = listOf(entity("file-1")))
                fake.observeForBook(BookId("book-1")).test {
                    awaitItem().single().status shouldBe DownloadStatus.QUEUED
                    fake.markDownloading("file-1", startedAt = 100L)
                    val downloading = awaitItem().single()
                    downloading.status shouldBe DownloadStatus.DOWNLOADING
                    downloading.startedAt shouldBe 100L
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("updateProgress updates byte counts") {
            runTest {
                val fake = FakeDownloadRepository(initial = listOf(entity("file-1")))
                val result = fake.updateProgress("file-1", downloadedBytes = 250L, totalBytes = 1000L)
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                fake.entities.single().downloadedBytes shouldBe 250L
            }
        }

        test("markCompleted sets state and downloadedBytes equals totalBytes") {
            runTest {
                val fake = FakeDownloadRepository(initial = listOf(entity("file-1")))
                fake.markCompleted("file-1", localPath = "/tmp/file-1.mp3", completedAt = 500L)
                fake.observeForBook(BookId("book-1")).test {
                    val completed = awaitItem().single()
                    completed.status shouldBe DownloadStatus.COMPLETED
                    completed.localPath shouldBe "/tmp/file-1.mp3"
                    completed.completedAt shouldBe 500L
                    completed.downloadedBytes shouldBe completed.totalBytes
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("markCancelled writes CANCELLED state") {
            runTest {
                val fake = FakeDownloadRepository(initial = listOf(entity("file-1", state = DownloadState.DOWNLOADING)))
                fake.markCancelled("file-1")
                fake.observeForBook(BookId("book-1")).test {
                    awaitItem().single().status shouldBe DownloadStatus.CANCELLED
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("markFailed sets state and error message") {
            runTest {
                val fake = FakeDownloadRepository(initial = listOf(entity("file-1")))
                fake.markFailed("file-1", DownloadError.DownloadFailed(debugInfo = "test failure"))
                fake.observeForBook(BookId("book-1")).test {
                    val failed = awaitItem().single()
                    failed.status shouldBe DownloadStatus.FAILED
                    (failed.errorMessage?.contains("Download") == true) shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("enqueueForBook returns Started by default") {
            runTest {
                val fake = FakeDownloadRepository()
                val result = fake.enqueueForBook(BookId("book-1"))
                result.shouldBeInstanceOf<AppResult.Success<DownloadOutcome>>()
                result.data shouldBe DownloadOutcome.Started
            }
        }

        test("enqueueForBook respects injected failure") {
            runTest {
                val fake =
                    FakeDownloadRepository(
                        enqueueFailure = { _ ->
                            AppResult.Success(
                                DownloadOutcome.InsufficientStorage(requiredBytes = 1000, availableBytes = 500),
                            )
                        },
                    )
                val result = fake.enqueueForBook(BookId("book-1"))
                result.shouldBeInstanceOf<AppResult.Success<DownloadOutcome>>()
                result.data.shouldBeInstanceOf<DownloadOutcome.InsufficientStorage>()
            }
        }

        test("aggregator returns NotDownloaded for empty book") {
            runTest {
                val fake = FakeDownloadRepository()
                fake.observeBookStatus(BookId("book-1")).test {
                    awaitItem().shouldBeInstanceOf<BookDownloadStatus.NotDownloaded>()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("aggregator returns Completed when all files complete") {
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
                    val status = awaitItem().shouldBeInstanceOf<BookDownloadStatus.Completed>()
                    status.totalBytes shouldBe 2000L
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("aggregator returns Failed when any file failed") {
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
                    val status = awaitItem().shouldBeInstanceOf<BookDownloadStatus.Failed>()
                    status.partiallyDownloadedFiles shouldBe 1
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("aggregator returns InProgress for mixed downloading and queued") {
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
                    val status = awaitItem().shouldBeInstanceOf<BookDownloadStatus.InProgress>()
                    status.downloadingFiles shouldBe 1
                    status.totalFiles shouldBe 2
                    status.downloadedBytes shouldBe 500L
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("aggregator returns Paused when all files paused") {
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
                    val status = awaitItem().shouldBeInstanceOf<BookDownloadStatus.Paused>()
                    status.pausedFiles shouldBe 2
                    status.downloadedBytes shouldBe 300L
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("getLocalPath returns null for non-completed file") {
            runTest {
                val fake =
                    FakeDownloadRepository(
                        initial = listOf(entity("file-1", state = DownloadState.DOWNLOADING)),
                    )
                fake.getLocalPath("file-1") shouldBe null
            }
        }

        test("getLocalPath returns path for completed file") {
            runTest {
                val fake = FakeDownloadRepository()
                fake.seed(
                    entity("file-1", state = DownloadState.COMPLETED).copy(localPath = "/tmp/file-1.mp3"),
                )
                fake.getLocalPath("file-1") shouldBe "/tmp/file-1.mp3"
            }
        }

        test("deleteForBook removes all entities for that book") {
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
                fake.entities.size shouldBe 1
                fake.entities.single().bookId shouldBe "book-2"
            }
        }
    })
