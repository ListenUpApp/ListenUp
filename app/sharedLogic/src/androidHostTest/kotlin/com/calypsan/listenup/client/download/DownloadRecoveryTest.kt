package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun row(
    audioFileId: String,
    state: DownloadState,
) = DownloadEntity(
    audioFileId = audioFileId,
    bookId = "b1",
    filename = "$audioFileId.m4b",
    fileIndex = 0,
    state = state,
    localPath = null,
    totalBytes = 4_096L,
    downloadedBytes = 0,
    queuedAt = 1_700_000_000_000L,
    startedAt = null,
    completedAt = null,
    errorMessage = null,
)

/**
 * Which incomplete rows recovery resets to QUEUED and re-enqueues. A row whose worker is running
 * must be left alone, or a live download reads "Queued" for the rest of the file.
 */
class DownloadRecoveryTest :
    FunSpec({
        test("a row whose worker is running is not demoted to QUEUED") {
            val running = row("af1", DownloadState.DOWNLOADING)

            rowsToRequeue(listOf(running), runningAudioFileIds = setOf("af1")) shouldBe emptyList()
        }

        test("a stalled DOWNLOADING row with no running work is re-queued") {
            val stalled = row("af1", DownloadState.DOWNLOADING)

            rowsToRequeue(listOf(stalled), runningAudioFileIds = emptySet()) shouldBe listOf(stalled)
        }

        test("only the running row is skipped among several") {
            val running = row("af1", DownloadState.DOWNLOADING)
            val paused = row("af2", DownloadState.PAUSED)
            val queued = row("af3", DownloadState.QUEUED)

            rowsToRequeue(listOf(running, paused, queued), runningAudioFileIds = setOf("af1")) shouldBe
                listOf(paused, queued)
        }
    })
