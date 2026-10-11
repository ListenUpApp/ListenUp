package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DownloadActionTest :
    FunSpec({
        val size = 1_288_490_189L // 1.2 GB as formatFileSize prints it

        test("not downloaded: a Download action whose label carries the file size, never the length") {
            val action = downloadActionFor(BookDownloadStatus.NotDownloaded("b1"), isWaitingForWifi = false, sizeBytes = size)
            action.kind shouldBe DownloadActionKind.Download
            action.sizeLabel shouldBe "1.2 GB"
            action.tap shouldBe DownloadTap.Start
        }

        test("in flight with files moving: a cancellable progress with its percent") {
            val inFlight =
                BookDownloadStatus.InProgress(
                    bookId = "b1",
                    totalFiles = 4,
                    downloadingFiles = 1,
                    completedFiles = 1,
                    totalBytes = 1000,
                    downloadedBytes = 420,
                )
            val action = downloadActionFor(inFlight, isWaitingForWifi = false, sizeBytes = size)
            action.kind shouldBe DownloadActionKind.Downloading(percent = 42)
            action.tap shouldBe DownloadTap.Cancel
        }

        test("waiting for Wi-Fi says so and can be cancelled") {
            val queued = BookDownloadStatus.InProgress("b1", 4, 0, 0, 1000, 0)
            val action = downloadActionFor(queued, isWaitingForWifi = true, sizeBytes = size)
            action.kind shouldBe DownloadActionKind.WaitingForWifi
            action.tap shouldBe DownloadTap.Cancel
        }

        test("queued with nothing moving is Queued") {
            val queued = BookDownloadStatus.InProgress("b1", 4, 0, 0, 1000, 0)
            downloadActionFor(queued, isWaitingForWifi = false, sizeBytes = size).kind shouldBe DownloadActionKind.Queued
        }

        test("downloaded offers removal") {
            val done = downloadActionFor(BookDownloadStatus.Completed("b1", totalBytes = size), false, size)
            done.kind shouldBe DownloadActionKind.Downloaded
            done.tap shouldBe DownloadTap.Remove
        }

        test("failed or paused offers a retry") {
            val failed = downloadActionFor(BookDownloadStatus.Failed("b1", errorMessage = "x", partiallyDownloadedFiles = 0), false, size)
            failed.kind shouldBe DownloadActionKind.Retry
            failed.tap shouldBe DownloadTap.Start
            val paused = BookDownloadStatus.Paused("b1", pausedFiles = 2, completedFiles = 1, downloadedBytes = 10, totalBytes = 100)
            downloadActionFor(paused, false, size).tap shouldBe DownloadTap.Start
        }
    })
