package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadFailureReason
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Book Detail's Download control in every state of the approved canvas (`A-BD-States`): the button does the state's
 * main thing, and the line under the row says where things stand, offering Cancel whenever the button does not.
 */
class DownloadActionTest :
    FunSpec({
        val size = 1_288_490_189L // 1.2 GB as formatFileSize prints it
        val mib = 1024L * 1024

        fun inProgress(downloading: Int) =
            BookDownloadStatus.InProgress(
                bookId = "b1",
                totalFiles = 14,
                downloadingFiles = downloading,
                completedFiles = 2,
                totalBytes = 640 * mib,
                downloadedBytes = 212 * mib,
            )

        fun paused(reason: DownloadPauseReason) =
            BookDownloadStatus.Paused(
                bookId = "b1",
                pausedFiles = 12,
                completedFiles = 2,
                downloadedBytes = 212 * mib,
                totalBytes = 640 * mib,
                reason = reason,
            )

        test("not downloaded: a Download action whose label carries the file size, never the length, and no line") {
            val action = downloadActionFor(BookDownloadStatus.NotDownloaded("b1"), isWaitingForWifi = false, sizeBytes = size)
            action.kind shouldBe DownloadActionKind.Download
            action.sizeLabel shouldBe "1.2 GB"
            action.tap shouldBe DownloadTap.Start
            action.statusLine shouldBe null
        }

        test("downloading: the button pauses, and the line says how far it is with Cancel") {
            val action = downloadActionFor(inProgress(downloading = 1), isWaitingForWifi = false, sizeBytes = size)
            action.kind shouldBe DownloadActionKind.Downloading(percent = 33)
            action.tap shouldBe DownloadTap.Pause
            action.statusLine shouldBe DownloadStatusLine.Downloading("212 of 640 MB")
            action.statusLine?.offersCancel shouldBe true
        }

        test("queued: the button cancels, as today, and the line says it starts after the current download") {
            val action = downloadActionFor(inProgress(downloading = 0), isWaitingForWifi = false, sizeBytes = size)
            action.kind shouldBe DownloadActionKind.Queued
            action.tap shouldBe DownloadTap.Cancel
            action.statusLine shouldBe DownloadStatusLine.Queued
            action.statusLine?.offersCancel shouldBe false
        }

        test("waiting for Wi-Fi: the button cancels, and the line says downloads use Wi-Fi only") {
            val action = downloadActionFor(inProgress(downloading = 0), isWaitingForWifi = true, sizeBytes = size)
            action.kind shouldBe DownloadActionKind.WaitingForWifi
            action.tap shouldBe DownloadTap.Cancel
            action.statusLine shouldBe DownloadStatusLine.WaitingForWifi
        }

        test("stopped by the system: the button cancels, and the line says it resumes automatically") {
            val action = downloadActionFor(paused(DownloadPauseReason.INTERRUPTED), isWaitingForWifi = false, sizeBytes = size)
            action.kind shouldBe DownloadActionKind.WaitingToResume
            action.tap shouldBe DownloadTap.Cancel
            action.statusLine shouldBe DownloadStatusLine.Waiting
        }

        test("paused by you: the button resumes, and the line says what is saved with Cancel") {
            val action = downloadActionFor(paused(DownloadPauseReason.PAUSED_BY_YOU), isWaitingForWifi = true, sizeBytes = size)
            action.kind shouldBe DownloadActionKind.PausedByYou(percent = 33)
            action.tap shouldBe DownloadTap.Start
            action.statusLine shouldBe DownloadStatusLine.Paused("212 of 640 MB")
            action.statusLine?.offersCancel shouldBe true
        }

        test("failed: the button retries, and the line gives the reason with Cancel") {
            val failed =
                BookDownloadStatus.Failed(
                    bookId = "b1",
                    errorMessage = "Not enough storage space.",
                    partiallyDownloadedFiles = 0,
                    reason = DownloadFailureReason.NOT_ENOUGH_SPACE,
                )
            val action = downloadActionFor(failed, isWaitingForWifi = false, sizeBytes = size)
            action.kind shouldBe DownloadActionKind.Retry
            action.tap shouldBe DownloadTap.Start
            action.statusLine shouldBe DownloadStatusLine.Failed(DownloadFailureReason.NOT_ENOUGH_SPACE)
            action.statusLine?.offersCancel shouldBe true
        }

        test("downloaded: the button removes (the screen confirms), and there is no line") {
            val done = downloadActionFor(BookDownloadStatus.Completed("b1", totalBytes = size), false, size)
            done.kind shouldBe DownloadActionKind.Downloaded
            done.tap shouldBe DownloadTap.Remove
            done.statusLine shouldBe null
        }
    })
