package com.calypsan.listenup.client.download

import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import com.calypsan.listenup.client.data.local.db.MAX_DOWNLOAD_RETRIES
import com.calypsan.listenup.client.domain.model.DownloadPauseReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * What a launch or a foreground does with the downloads the database remembers and the transfers
 * the system is still holding. The system's task list survives the process; the database is what
 * the person asked for. Reconciling the two decides, per file, whether to leave a transfer alone,
 * stop it, move it, resume it from where it stopped, or ask the server for it again.
 */
class DownloadReconcilerTest :
    FunSpec({
        val now = 1_800_000_000L

        fun plan(
            rows: List<DownloadEntity>,
            tasks: List<LiveDownloadTask> = emptyList(),
            blobs: Map<String, Long?> = emptyMap(),
            network: DownloadNetwork = DownloadNetwork.Any,
        ) = reconcileDownloads(rows, tasks, blobs, network, nowEpochSeconds = now)

        test("a transfer the system is still running for a wanted file is left alone") {
            val task = task("f1")

            val plan = plan(rows = listOf(row("f1", DownloadState.DOWNLOADING)), tasks = listOf(task))

            plan shouldBe ReconcilePlan(adopt = listOf(task))
        }

        test("a transfer for a file nobody wants any more is stopped") {
            val tasks =
                listOf(task("cancelled", id = 1u), task("deleted", id = 2u), task("done", id = 3u), task("unknown", id = 4u))
            val rows =
                listOf(
                    row("cancelled", DownloadState.CANCELLED),
                    row("deleted", DownloadState.DELETED),
                    row("done", DownloadState.COMPLETED),
                )

            plan(rows, tasks).cancel shouldBe tasks
        }

        test("a transfer for a file you paused is stopped, and the file stays paused") {
            val task = task("f1")

            val plan = plan(listOf(row("f1", DownloadState.PAUSED, DownloadPauseReason.PAUSED_BY_YOU)), listOf(task))

            plan shouldBe ReconcilePlan(cancel = listOf(task))
        }

        test("a task with no tag of ours is stopped") {
            val stranger = LiveDownloadTask(TaskKey(DownloadNetwork.Any, 9u), tag = null)

            plan(rows = emptyList(), tasks = listOf(stranger)).cancel shouldBe listOf(stranger)
        }

        test("two transfers of one file keep the first and stop the second") {
            val first = task("f1", id = 1u)
            val second = task("f1", id = 2u)

            val plan = plan(listOf(row("f1", DownloadState.DOWNLOADING)), listOf(first, second))

            plan.adopt shouldBe listOf(first)
            plan.cancel shouldBe listOf(second)
        }

        test("a transfer in the session the Wi-Fi-only preference no longer selects is moved") {
            val task = task("f1", network = DownloadNetwork.Any)

            val plan =
                plan(listOf(row("f1", DownloadState.DOWNLOADING)), listOf(task), network = DownloadNetwork.Unmetered)

            plan shouldBe ReconcilePlan(migrate = listOf(task))
        }

        test("a wanted file with no transfer resumes from its resume data") {
            val row = row("f1", DownloadState.QUEUED, DownloadPauseReason.APP_CLOSED)

            plan(listOf(row), blobs = mapOf("f1" to now + 3_600)) shouldBe ReconcilePlan(resumeFromBlob = listOf(row))
        }

        test("resume data whose expiry nobody recorded is still tried") {
            val row = row("f1", DownloadState.PAUSED, DownloadPauseReason.INTERRUPTED)

            plan(listOf(row), blobs = mapOf("f1" to null)).resumeFromBlob shouldBe listOf(row)
        }

        test("resume data whose URL has expired is thrown away and the server asked again") {
            val row = row("f1", DownloadState.QUEUED)

            val plan = plan(listOf(row), blobs = mapOf("f1" to now - 1))

            plan.resumeFromBlob shouldBe emptyList()
            plan.prepareByBook shouldBe mapOf("b1" to listOf(row))
            plan.discardBlobs shouldBe setOf("f1")
        }

        test("wanted files with nothing to resume from are asked for once per book, not once per file") {
            val rows =
                listOf(
                    row("f1", DownloadState.QUEUED, book = "b1"),
                    row("f2", DownloadState.DOWNLOADING, book = "b1"),
                    row("f3", DownloadState.PAUSED, DownloadPauseReason.NETWORK_LOST, book = "b2"),
                    row("f4", DownloadState.FAILED, book = "b2"),
                )

            plan(rows).prepareByBook shouldBe mapOf("b1" to rows.take(2), "b2" to rows.drop(2))
        }

        test("a file you paused, a finished one and one out of retries are left as they are") {
            val rows =
                listOf(
                    row("paused", DownloadState.PAUSED, DownloadPauseReason.PAUSED_BY_YOU),
                    row("done", DownloadState.COMPLETED),
                    row("spent", DownloadState.FAILED, retries = MAX_DOWNLOAD_RETRIES),
                )

            plan(rows, blobs = mapOf("paused" to null)) shouldBe ReconcilePlan()
        }

        test("resume data for a file nobody wants is thrown away") {
            plan(listOf(row("gone", DownloadState.CANCELLED)), blobs = mapOf("gone" to null, "orphan" to null))
                .discardBlobs shouldBe setOf("gone", "orphan")
        }
    })

private fun task(
    audioFileId: String,
    id: ULong = 1u,
    network: DownloadNetwork = DownloadNetwork.Any,
): LiveDownloadTask =
    LiveDownloadTask(TaskKey(network, id), DownloadTaskTag(bookId = "b1", audioFileId = audioFileId, filename = "$audioFileId.m4b"))

private fun row(
    id: String,
    state: DownloadState,
    reason: DownloadPauseReason? = null,
    book: String = "b1",
    retries: Int = 0,
): DownloadEntity =
    DownloadEntity(
        audioFileId = id,
        bookId = book,
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
        retryCount = retries,
        pauseReason = reason,
    )
