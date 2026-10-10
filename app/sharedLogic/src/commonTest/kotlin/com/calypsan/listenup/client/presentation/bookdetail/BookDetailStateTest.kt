package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.BookAvailability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val available =
    BookAvailability.State(
        downloadStatus = BookDownloadStatus.NotDownloaded("book-1"),
        isPlaybackAvailable = true,
        canPlay = true,
        canDownload = true,
        showServerWarning = true,
        isWaitingForWifi = false,
    )

private fun position(
    ms: Long,
    finished: Boolean = false,
) = PlaybackPosition(
    bookId = "book-1",
    positionMs = ms,
    playbackSpeed = 1f,
    hasCustomSpeed = false,
    volumeBoostDb = 0f,
    hasCustomBoost = false,
    measuredGainDb = null,
    updatedAtMs = 0L,
    syncedAtMs = null,
    lastPlayedAtMs = 0L,
    isFinished = finished,
)

internal fun snapshot(
    detail: BookDetail = TestData.bookDetail(id = "book-1", duration = 3_600_000L),
    chapters: List<Chapter> = TestData.chapters(count = 4, chapterDuration = 900_000L),
    position: PlaybackPosition? = null,
    availability: BookAvailability.State = available,
    isHeld: Boolean = false,
    visibility: BookVisibility? = null,
) = BookSnapshot(detail, chapters, position, availability, isHeld, visibility)

class BookDetailStateTest :
    FunSpec({
        context("toReady") {
            test("a held book can neither play nor download, and raises no server warning") {
                val ready = snapshot(isHeld = true).toReady(BookDetailAmbient())
                ready.isHeld shouldBe true
                ready.canPlay shouldBe false
                ready.canDownload shouldBe false
                ready.showServerWarning shouldBe false
            }

            test("an ordinary book keeps every availability answer") {
                val ready = snapshot().toReady(BookDetailAmbient())
                ready.canPlay shouldBe true
                ready.canDownload shouldBe true
                ready.showServerWarning shouldBe true
                ready.downloadStatus shouldBe BookDownloadStatus.NotDownloaded("book-1")
            }

            test("the account's facts land on Ready") {
                val tags = listOf(TestData.tag(id = "t1"))
                val ready =
                    snapshot().toReady(BookDetailAmbient(isAdmin = true, canEditMetadata = true, allTags = tags))
                ready.isAdmin shouldBe true
                ready.canEditMetadata shouldBe true
                ready.allTags shouldBe tags
            }

            test("the chapter you are in is marked once you have started, and none before") {
                snapshot(position = position(1_000_000L)).toReady(BookDetailAmbient()).chapters.map { it.isCurrent } shouldBe
                    listOf(false, true, false, false)
                snapshot().toReady(BookDetailAmbient()).chapters.none { it.isCurrent } shouldBe true
            }

            test("a finished book is complete, with no progress bar and no time left") {
                val ready = snapshot(position = position(3_600_000L, finished = true)).toReady(BookDetailAmbient())
                ready.isComplete shouldBe true
                ready.progress shouldBe null
                ready.timeRemainingFormatted shouldBe null
            }

            test("a book in progress carries its fraction") {
                snapshot(position = position(900_000L)).toReady(BookDetailAmbient()).progress shouldBe 0.25f
            }
        }
    })
