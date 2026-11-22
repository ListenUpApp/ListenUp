package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.repository.aggregateBookDownloadStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * A book's status says why it is not moving. Before, every stopped download read "Paused" with a Resume that did
 * nothing new; now the reason travels with it, so a system stop reads as a wait and only the person's pause asks them.
 */
class DownloadAggregationReasonTest :
    FunSpec({
        fun row(
            id: String,
            state: DownloadState,
            reason: DownloadPauseReason? = null,
            lastErrorCode: String? = null,
            downloaded: Long = 0,
        ) = DownloadEntity(
            audioFileId = id,
            bookId = "b1",
            filename = "$id.mp3",
            fileIndex = id.removePrefix("f").toInt(),
            state = state,
            localPath = null,
            totalBytes = 100,
            downloadedBytes = downloaded,
            queuedAt = 0,
            startedAt = null,
            completedAt = null,
            errorMessage = if (state == DownloadState.FAILED) "Download failed." else null,
            pauseReason = reason,
            lastErrorCode = lastErrorCode,
        )

        test("a file the system stopped makes the book wait to resume, though files behind it are queued") {
            val status =
                aggregateBookDownloadStatus(
                    "b1",
                    listOf(
                        row("f0", DownloadState.COMPLETED, downloaded = 100),
                        row("f1", DownloadState.PAUSED, DownloadPauseReason.INTERRUPTED, downloaded = 40),
                        row("f2", DownloadState.QUEUED),
                    ),
                )
            val paused = status.shouldBeInstanceOf<BookDownloadStatus.Paused>()
            paused.reason shouldBe DownloadPauseReason.INTERRUPTED
            paused.downloadedBytes shouldBe 140
        }

        test("the person's pause wins over a wait on any other file") {
            val status =
                aggregateBookDownloadStatus(
                    "b1",
                    listOf(
                        row("f0", DownloadState.PAUSED, DownloadPauseReason.NETWORK_LOST),
                        row("f1", DownloadState.PAUSED, DownloadPauseReason.PAUSED_BY_YOU),
                    ),
                )
            status.shouldBeInstanceOf<BookDownloadStatus.Paused>().reason shouldBe DownloadPauseReason.PAUSED_BY_YOU
        }

        test("a moving file keeps the book in progress whatever else is paused") {
            aggregateBookDownloadStatus(
                "b1",
                listOf(
                    row("f0", DownloadState.DOWNLOADING, downloaded = 10),
                    row("f1", DownloadState.PAUSED, DownloadPauseReason.INTERRUPTED),
                ),
            ).shouldBeInstanceOf<BookDownloadStatus.InProgress>()
        }

        test("a failure says why from the failed file's error code, and what a cancel would discard") {
            val failed =
                aggregateBookDownloadStatus(
                    "b1",
                    listOf(
                        row("f0", DownloadState.COMPLETED, downloaded = 100),
                        row("f1", DownloadState.FAILED, lastErrorCode = "DOWNLOAD_INSUFFICIENT_STORAGE", downloaded = 30),
                    ),
                ).shouldBeInstanceOf<BookDownloadStatus.Failed>()
            failed.reason shouldBe DownloadFailureReason.NOT_ENOUGH_SPACE
            failed.downloadedBytes shouldBe 130
            failed.totalBytes shouldBe 200
        }
    })
