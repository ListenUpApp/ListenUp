package com.calypsan.listenup.client.download

import androidx.work.NetworkType
import com.calypsan.listenup.client.data.local.db.DownloadEntity
import com.calypsan.listenup.client.data.local.db.DownloadState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe

/**
 * Tests for the single download work-request builder.
 *
 * The Wi-Fi-only preference used to be snapshotted into each work request at enqueue time and
 * never re-read, so switching the preference on left queued books downloading over cellular while
 * the book-detail button — which reads the live preference — showed "waiting for Wi-Fi". These
 * tests pin the two halves of the fix: the constraint always comes from the argument, and the
 * WorkManager identifier strings stay byte-identical to the ones already persisted on devices.
 */
private fun downloadEntity(
    audioFileId: String = "af1",
    bookId: String = "b1",
    filename: String = "chapter-01.m4b",
    totalBytes: Long = 4_096L,
) = DownloadEntity(
    audioFileId = audioFileId,
    bookId = bookId,
    filename = filename,
    fileIndex = 0,
    state = DownloadState.QUEUED,
    localPath = null,
    totalBytes = totalBytes,
    downloadedBytes = 0,
    queuedAt = 1_700_000_000_000L,
    startedAt = null,
    completedAt = null,
    errorMessage = null,
    retryCount = 0,
)

class DownloadWorkRequestTest :
    FunSpec({

        context("the network constraint follows the live preference") {
            test("wifiOnly = true requires an unmetered network") {
                downloadNetworkConstraints(wifiOnly = true).requiredNetworkType shouldBe NetworkType.UNMETERED
            }

            test("wifiOnly = false accepts any connected network") {
                downloadNetworkConstraints(wifiOnly = false).requiredNetworkType shouldBe NetworkType.CONNECTED
            }

            test("a request built with wifiOnly = true carries the unmetered constraint") {
                buildDownloadRequest(downloadEntity(), wifiOnly = true)
                    .workSpec.constraints.requiredNetworkType shouldBe NetworkType.UNMETERED
            }

            test("a request built with wifiOnly = false carries the connected constraint") {
                buildDownloadRequest(downloadEntity(), wifiOnly = false)
                    .workSpec.constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
            }
        }

        context("the request carries everything the worker needs") {
            test("input data carries the entity's four worker keys") {
                val entity = downloadEntity(audioFileId = "af9", bookId = "b9", filename = "part-09.mp3", totalBytes = 99L)

                val input = buildDownloadRequest(entity, wifiOnly = false).workSpec.input

                input.getString(DownloadWorker.KEY_AUDIO_FILE_ID) shouldBe "af9"
                input.getString(DownloadWorker.KEY_BOOK_ID) shouldBe "b9"
                input.getString(DownloadWorker.KEY_FILENAME) shouldBe "part-09.mp3"
                input.getLong(DownloadWorker.KEY_FILE_SIZE, -1L) shouldBe 99L
            }

            test("tags cover both the book-wide and the per-file cancel unit") {
                val entity = downloadEntity(audioFileId = "af2", bookId = "b2")

                buildDownloadRequest(entity, wifiOnly = true).tags shouldContainAll
                    setOf("download_b2", "download_file_af2")
            }
        }

        context("the WorkManager identifier strings are load-bearing") {
            // These persist in WorkManager's own database across app upgrades: a rename orphans
            // in-flight work, so pin the exact strings rather than the shape.
            test("fileWorkName is the unique-work name") {
                fileWorkName("af1") shouldBe "download_af1"
            }

            test("bookTag is the book-wide cancel tag") {
                bookTag("b1") shouldBe "download_b1"
            }

            test("fileCancelTag is distinct from the unique-work name") {
                fileCancelTag("af1") shouldBe "download_file_af1"
            }
        }

        context("re-applying the constraint to already-enqueued rows") {
            test("nothing incomplete means nothing to re-enqueue") {
                constraintRefreshWork(rows = emptyList(), wifiOnly = true).shouldBeEmpty()
            }

            test("every row yields its own unique-work name, in order") {
                val rows =
                    listOf(
                        downloadEntity(audioFileId = "af1"),
                        downloadEntity(audioFileId = "af2"),
                        downloadEntity(audioFileId = "af3"),
                    )

                constraintRefreshWork(rows, wifiOnly = false).map { it.first } shouldBe
                    listOf("download_af1", "download_af2", "download_af3")
            }

            test("turning Wi-Fi-only on puts every row on an unmetered network") {
                val rows = listOf(downloadEntity(audioFileId = "af1"), downloadEntity(audioFileId = "af2"))

                constraintRefreshWork(rows, wifiOnly = true)
                    .map { it.second.workSpec.constraints.requiredNetworkType }
                    .shouldContainOnly(NetworkType.UNMETERED)
            }

            test("turning Wi-Fi-only off puts every row back on any connected network") {
                val rows = listOf(downloadEntity(audioFileId = "af1"), downloadEntity(audioFileId = "af2"))

                constraintRefreshWork(rows, wifiOnly = false)
                    .map { it.second.workSpec.constraints.requiredNetworkType }
                    .shouldContainOnly(NetworkType.CONNECTED)
            }

            test("the constraint comes from the argument, never from the row") {
                // The regression: rows enqueued while the preference was off carry no record of
                // that policy, so the only honest source for the new constraint is the argument.
                // If a future `wifiOnly` column on DownloadEntity ever drives this, it fails here.
                val enqueuedWhileCellularWasAllowed =
                    listOf(downloadEntity(audioFileId = "af1"), downloadEntity(audioFileId = "af2"))

                constraintRefreshWork(enqueuedWhileCellularWasAllowed, wifiOnly = true)
                    .map { it.second.workSpec.constraints.requiredNetworkType }
                    .shouldContainOnly(NetworkType.UNMETERED)
            }
        }
    })
