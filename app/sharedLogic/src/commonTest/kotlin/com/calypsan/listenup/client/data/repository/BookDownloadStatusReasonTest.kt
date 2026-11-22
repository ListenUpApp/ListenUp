package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The status a book's rows reduce to says why a stopped download is stopped, so a surface can tell
 * "you paused this" (offer Resume) from "the system paused this" (it resumes by itself), and can say
 * that a download is picking up again after ListenUp was closed.
 */
class BookDownloadStatusReasonTest :
    FunSpec({
        test("a book you paused says so, even beside files the system paused") {
            val status =
                aggregateBookDownloadStatus(
                    "b1",
                    listOf(
                        row("f1", DownloadState.PAUSED, DownloadPauseReason.NETWORK_LOST),
                        row("f2", DownloadState.PAUSED, DownloadPauseReason.PAUSED_BY_YOU),
                    ),
                )

            status.shouldBeInstanceOf<BookDownloadStatus.Paused>().reason shouldBe DownloadPauseReason.PAUSED_BY_YOU
        }

        test("a book the system paused carries the system's reason") {
            val status =
                aggregateBookDownloadStatus("b1", listOf(row("f1", DownloadState.PAUSED, DownloadPauseReason.INTERRUPTED)))

            status.shouldBeInstanceOf<BookDownloadStatus.Paused>().reason shouldBe DownloadPauseReason.INTERRUPTED
        }

        test("a cancelled partial has no pause reason: nobody is going to resume it") {
            val status =
                aggregateBookDownloadStatus(
                    "b1",
                    listOf(row("f1", DownloadState.COMPLETED, null), row("f2", DownloadState.CANCELLED, null)),
                )

            status.shouldBeInstanceOf<BookDownloadStatus.Paused>().reason.shouldBeNull()
        }

        test("a download picking up again after ListenUp was closed says so until bytes move") {
            val resuming =
                aggregateBookDownloadStatus(
                    "b1",
                    listOf(row("f1", DownloadState.COMPLETED, null), row("f2", DownloadState.QUEUED, DownloadPauseReason.APP_CLOSED)),
                )
            resuming.shouldBeInstanceOf<BookDownloadStatus.InProgress>().resumingAfterClose shouldBe true

            val moving =
                aggregateBookDownloadStatus(
                    "b1",
                    listOf(
                        row("f1", DownloadState.DOWNLOADING, null),
                        row("f2", DownloadState.QUEUED, DownloadPauseReason.APP_CLOSED),
                    ),
                )
            moving.shouldBeInstanceOf<BookDownloadStatus.InProgress>().resumingAfterClose shouldBe false
        }

        test("an ordinary queued download is not resuming after a close") {
            aggregateBookDownloadStatus("b1", listOf(row("f1", DownloadState.QUEUED, null)))
                .shouldBeInstanceOf<BookDownloadStatus.InProgress>()
                .resumingAfterClose shouldBe false
        }
    })

private fun row(
    id: String,
    state: DownloadState,
    reason: DownloadPauseReason?,
): DownloadEntity =
    DownloadEntity(
        audioFileId = id,
        bookId = "b1",
        filename = "$id.m4b",
        fileIndex = 0,
        state = state,
        localPath = null,
        totalBytes = 100L,
        downloadedBytes = 10L,
        queuedAt = 0L,
        startedAt = null,
        completedAt = null,
        errorMessage = null,
        retryCount = 0,
        pauseReason = reason,
    )
