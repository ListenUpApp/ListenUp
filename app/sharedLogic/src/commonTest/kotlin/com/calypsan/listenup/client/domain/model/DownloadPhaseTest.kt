package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.client.presentation.bookdetail.byteProgressLabel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The one rule every download surface reads. A download that is not moving says why, in the person's terms: behind
 * another book, held by Wi-Fi-only, waiting on the system, or paused by them; and only the last needs them.
 */
class DownloadPhaseTest :
    FunSpec({
        fun inProgress(
            downloading: Int,
            downloaded: Long = 212,
            total: Long = 640,
        ) = BookDownloadStatus.InProgress(
            bookId = "b1",
            totalFiles = 14,
            downloadingFiles = downloading,
            completedFiles = 2,
            totalBytes = total,
            downloadedBytes = downloaded,
        )

        fun paused(reason: DownloadPauseReason?) =
            BookDownloadStatus.Paused(
                bookId = "b1",
                pausedFiles = 1,
                completedFiles = 2,
                downloadedBytes = 212,
                totalBytes = 640,
                reason = reason,
            )

        test("bytes moving read Downloading with the saved share, whatever the network") {
            downloadPhaseOf(inProgress(downloading = 1), wifiBlocked = true) shouldBe
                DownloadPhase.Downloading(downloadedBytes = 212, totalBytes = 640)
            DownloadPhase.Downloading(downloadedBytes = 212, totalBytes = 640).percent shouldBe 33
        }

        test("nothing moving reads Queued, or Waiting for Wi-Fi while Wi-Fi-only holds it") {
            downloadPhaseOf(inProgress(downloading = 0), wifiBlocked = false) shouldBe DownloadPhase.Queued
            downloadPhaseOf(inProgress(downloading = 0), wifiBlocked = true) shouldBe DownloadPhase.WaitingForWifi
        }

        listOf(
            DownloadPauseReason.NETWORK_LOST,
            DownloadPauseReason.INTERRUPTED,
            DownloadPauseReason.SIGN_IN_REQUIRED,
        ).forEach { reason ->
            test("a stop the platform resumes ($reason) waits to resume, or for Wi-Fi while Wi-Fi-only holds it") {
                downloadPhaseOf(paused(reason), wifiBlocked = false) shouldBe DownloadPhase.WaitingToResume
                downloadPhaseOf(paused(reason), wifiBlocked = true) shouldBe DownloadPhase.WaitingForWifi
            }
        }

        test("only the person's own pause reads Paused by you, and it stays theirs off Wi-Fi") {
            downloadPhaseOf(paused(DownloadPauseReason.PAUSED_BY_YOU), wifiBlocked = true) shouldBe
                DownloadPhase.PausedByYou(downloadedBytes = 212, totalBytes = 640)
        }

        test("a cancelled partial with no recorded reason waits for the person, as Paused by you") {
            downloadPhaseOf(paused(reason = null), wifiBlocked = false) shouldBe
                DownloadPhase.PausedByYou(downloadedBytes = 212, totalBytes = 640)
        }

        test("a failure carries its reason; completed and nothing-on-device read as such") {
            val failed =
                BookDownloadStatus.Failed(
                    bookId = "b1",
                    errorMessage = "Not enough storage space.",
                    partiallyDownloadedFiles = 0,
                    reason = DownloadFailureReason.NOT_ENOUGH_SPACE,
                )
            downloadPhaseOf(failed, wifiBlocked = false) shouldBe DownloadPhase.Failed(DownloadFailureReason.NOT_ENOUGH_SPACE)
            downloadPhaseOf(BookDownloadStatus.Completed("b1", totalBytes = 640), wifiBlocked = true) shouldBe
                DownloadPhase.Downloaded
            downloadPhaseOf(BookDownloadStatus.NotDownloaded("b1"), wifiBlocked = true) shouldBe DownloadPhase.NotDownloaded
        }

        test("a failed row's error code says what to do about it") {
            DownloadFailureReason.fromErrorCode("DOWNLOAD_INSUFFICIENT_STORAGE") shouldBe DownloadFailureReason.NOT_ENOUGH_SPACE
            DownloadFailureReason.fromErrorCode("TRANSPORT_SERVER_5XX") shouldBe DownloadFailureReason.SERVER_STOPPED_ANSWERING
            DownloadFailureReason.fromErrorCode("TRANSPORT_OUTCOME_UNKNOWN") shouldBe
                DownloadFailureReason.SERVER_STOPPED_ANSWERING
            DownloadFailureReason.fromErrorCode("DOWNLOAD_FAILED") shouldBe DownloadFailureReason.UNKNOWN
            DownloadFailureReason.fromErrorCode(null) shouldBe DownloadFailureReason.UNKNOWN
        }

        test("cancelling asks first above about 100 MB or a quarter of the book, and not below both") {
            cancelNeedsConfirmation(downloadedBytes = 100_000_001, totalBytes = 2_000_000_000) shouldBe true
            cancelNeedsConfirmation(downloadedBytes = 30_000_000, totalBytes = 100_000_000) shouldBe true
            cancelNeedsConfirmation(downloadedBytes = 20_000_000, totalBytes = 100_000_000) shouldBe false
            cancelNeedsConfirmation(downloadedBytes = 50_000_000, totalBytes = 0) shouldBe false
        }

        test("saved bytes read in the total's unit, named once") {
            val mib = 1024L * 1024
            byteProgressLabel(downloadedBytes = 212 * mib, totalBytes = 640 * mib) shouldBe "212 of 640 MB"
            byteProgressLabel(downloadedBytes = 1126 * mib, totalBytes = 1434 * mib) shouldBe "1.1 of 1.4 GB"
            byteProgressLabel(downloadedBytes = 0, totalBytes = 640 * mib) shouldBe "0 of 640 MB"
        }
    })
