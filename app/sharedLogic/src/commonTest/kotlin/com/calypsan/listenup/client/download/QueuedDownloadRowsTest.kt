package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun audioFile(
    id: String,
    index: Int,
) = AudioFileEntity(
    bookId = BookId("b1"),
    index = index,
    id = id,
    filename = "$id.m4b",
    format = "m4b",
    codec = "aac",
    duration = 60_000L,
    size = 1_000L,
)

/**
 * The rows a (re)download writes. Resuming a paused or failed file must not zero its progress: the
 * `.tmp` partial survives and the transfer continues from it, so the row has to say so too.
 */
class QueuedDownloadRowsTest :
    FunSpec({
        test("resuming a paused file keeps the bytes it already has and starts a fresh retry budget") {
            val first = audioFile("af1", index = 0)
            val paused =
                DownloadEntity(
                    audioFileId = "af1",
                    bookId = "b1",
                    filename = "af1.m4b",
                    fileIndex = 0,
                    state = DownloadState.PAUSED,
                    localPath = null,
                    totalBytes = 1_000L,
                    downloadedBytes = 400L,
                    queuedAt = 1L,
                    startedAt = 2L,
                    completedAt = null,
                    errorMessage = "Download failed.",
                    retryCount = 2,
                    pauseReason = DownloadPauseReason.INTERRUPTED,
                )

            val row =
                queuedDownloadRows(
                    bookId = "b1",
                    audioFiles = listOf(first),
                    toDownload = listOf(first),
                    existing = listOf(paused),
                    queuedAt = 10L,
                ).single()

            row.state shouldBe DownloadState.QUEUED
            row.downloadedBytes shouldBe 400L
            row.retryCount shouldBe 0
            row.pauseReason shouldBe null
            row.errorMessage shouldBe null
            row.queuedAt shouldBe 10L
        }

        test("a file with no row yet starts from zero at its position in the book") {
            val first = audioFile("af1", index = 0)
            val second = audioFile("af2", index = 1)

            val row =
                queuedDownloadRows(
                    bookId = "b1",
                    audioFiles = listOf(first, second),
                    toDownload = listOf(second),
                    existing = emptyList(),
                    queuedAt = 10L,
                ).single()

            row.audioFileId shouldBe "af2"
            row.fileIndex shouldBe 1
            row.downloadedBytes shouldBe 0L
            row.totalBytes shouldBe 1_000L
        }
    })
