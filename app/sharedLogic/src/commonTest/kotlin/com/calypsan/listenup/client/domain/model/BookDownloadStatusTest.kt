package com.calypsan.listenup.client.domain.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BookDownloadStatusTest :
    FunSpec({

        test("NotDownloaded carries only the bookId") {
            val status = BookDownloadStatus.NotDownloaded(bookId = "book-1")
            status.bookId shouldBe "book-1"
        }

        test("InProgress progress computes from downloadedBytes and totalBytes") {
            val status =
                BookDownloadStatus.InProgress(
                    bookId = "book-1",
                    totalFiles = 4,
                    downloadingFiles = 1,
                    completedFiles = 2,
                    totalBytes = 1000L,
                    downloadedBytes = 250L,
                )
            status.progress shouldBe 0.25f
        }

        test("InProgress progress is zero when totalBytes is zero") {
            val status =
                BookDownloadStatus.InProgress(
                    bookId = "book-1",
                    totalFiles = 1,
                    downloadingFiles = 1,
                    completedFiles = 0,
                    totalBytes = 0L,
                    downloadedBytes = 0L,
                )
            status.progress shouldBe 0f
        }

        test("Completed carries totalBytes") {
            val status = BookDownloadStatus.Completed(bookId = "book-1", totalBytes = 5000L)
            status.bookId shouldBe "book-1"
            status.totalBytes shouldBe 5000L
        }

        test("Failed carries error message and partiallyDownloadedFiles count") {
            val status =
                BookDownloadStatus.Failed(
                    bookId = "book-1",
                    errorMessage = "Network unreachable",
                    partiallyDownloadedFiles = 2,
                )
            status.errorMessage shouldBe "Network unreachable"
            status.partiallyDownloadedFiles shouldBe 2
        }

        test("Paused carries paused file count and progress numbers") {
            val status =
                BookDownloadStatus.Paused(
                    bookId = "book-1",
                    pausedFiles = 3,
                    downloadedBytes = 100L,
                    totalBytes = 500L,
                )
            status.pausedFiles shouldBe 3
            status.downloadedBytes shouldBe 100L
            status.totalBytes shouldBe 500L
        }

        test("sealed hierarchy enables exhaustive when matching") {
            val status: BookDownloadStatus = BookDownloadStatus.NotDownloaded("book-1")
            val description: String =
                when (status) {
                    is BookDownloadStatus.NotDownloaded -> "not downloaded"
                    is BookDownloadStatus.InProgress -> "in progress"
                    is BookDownloadStatus.Completed -> "completed"
                    is BookDownloadStatus.Failed -> "failed"
                    is BookDownloadStatus.Paused -> "paused"
                }
            description.isNotEmpty() shouldBe true
        }
    })
