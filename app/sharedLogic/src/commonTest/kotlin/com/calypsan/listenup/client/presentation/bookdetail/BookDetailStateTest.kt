package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.TestData
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.client.domain.model.BookDownloadStatus
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.repository.BookAvailability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

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

            test("the current chapter says whether this book is the one loaded in the player") {
                val reading = snapshot(position = position(1_000_000L))
                reading.toReady(BookDetailAmbient(loadedBookId = "book-1")).currentChapter?.isInPlayer shouldBe true
                reading.toReady(BookDetailAmbient(loadedBookId = "book-2")).currentChapter?.isInPlayer shouldBe false
                reading.toReady(BookDetailAmbient(loadedBookId = null)).currentChapter?.isInPlayer shouldBe false
            }

            test("a finished book marks no chapter as current, wherever its position was left") {
                listOf(0L, 1_000_000L, 3_600_000L).forEach { ms ->
                    val ready = snapshot(position = position(ms, finished = true)).toReady(BookDetailAmbient())
                    ready.chapters.none { it.isCurrent } shouldBe true
                    ready.currentChapter shouldBe null
                }
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

        context("bookDetailUiState") {
            val loaded = BookLoad.Loaded("book-1", snapshot())

            test("Loading until a book is asked for") {
                bookDetailUiState(null, null, BookDetailAmbient(), BookDetailOverlay(null), orderLatch = null) shouldBe
                    BookDetailUiState.Loading
            }

            test("Loading while the latest load is another book's — never that book's page") {
                bookDetailUiState("book-2", loaded, BookDetailAmbient(), BookDetailOverlay("book-2"), orderLatch = null) shouldBe
                    BookDetailUiState.Loading
                bookDetailUiState("book-1", null, BookDetailAmbient(), BookDetailOverlay("book-1"), orderLatch = null) shouldBe
                    BookDetailUiState.Loading
            }

            test("a book with no row is NotFound") {
                bookDetailUiState("book-1", BookLoad.Missing("book-1"), BookDetailAmbient(), BookDetailOverlay("book-1"), orderLatch = null)
                    .shouldBeInstanceOf<BookDetailUiState.Error>()
                    .error
                    .shouldBeInstanceOf<BookError.NotFound>()
            }

            test("the reader's overlay is laid on their book") {
                val ready =
                    bookDetailUiState(
                        "book-1",
                        loaded,
                        BookDetailAmbient(),
                        BookDetailOverlay("book-1", showShelfPicker = true, shelfError = "Full."),
                        orderLatch = null,
                    ).shouldBeInstanceOf<BookDetailUiState.Ready>()
                ready.showShelfPicker shouldBe true
                ready.shelfError shouldBe "Full."
            }

            test("an overlay left over from another book is ignored") {
                bookDetailUiState(
                    "book-1",
                    loaded,
                    BookDetailAmbient(),
                    BookDetailOverlay("book-0", showShelfPicker = true),
                    orderLatch = null,
                ).shouldBeInstanceOf<BookDetailUiState.Ready>()
                    .showShelfPicker shouldBe false
            }
        }

        context("withOverlay") {
            test("'restoring' shows only while the book is still stranded") {
                val restoring = BookDetailOverlay("book-1", isRestoringToAllBooks = true)
                snapshot(visibility = BookVisibility.Stranded)
                    .toReady(BookDetailAmbient())
                    .withOverlay(restoring)
                    .isRestoringToAllBooks shouldBe true
                snapshot(visibility = BookVisibility.Public)
                    .toReady(BookDetailAmbient())
                    .withOverlay(restoring)
                    .isRestoringToAllBooks shouldBe false
            }
        }

        context("retiredBy") {
            val busy =
                BookDetailOverlay(
                    "book-1",
                    showShelfPicker = true,
                    isReleasingFromInbox = true,
                    isRestoringToAllBooks = true,
                )

            test("Room speaking again keeps everything the reader has open") {
                val after = busy.retiredBy(BookLoad.Loaded("book-1", snapshot(visibility = BookVisibility.Stranded)))
                after.showShelfPicker shouldBe true
                after.isReleasingFromInbox shouldBe true
                after.isRestoringToAllBooks shouldBe true
            }

            test("a book no longer stranded ends 'restoring' for good") {
                busy
                    .retiredBy(BookLoad.Loaded("book-1", snapshot(visibility = BookVisibility.Public)))
                    .isRestoringToAllBooks shouldBe false
            }

            test("another book's load changes nothing") {
                busy.retiredBy(BookLoad.Loaded("book-2", snapshot())) shouldBe busy
            }

            test("a vanished row clears the overlay, so nothing reopens when the row comes back") {
                busy.retiredBy(BookLoad.Missing("book-1")) shouldBe BookDetailOverlay("book-1")
            }
        }

        context("BookDetailPage") {
            val restoring = BookDetailOverlay("book-1", visit = 3, isRestoringToAllBooks = true, showShelfPicker = true)

            test("Room's word and the overlay it retires arrive in one value") {
                val load = BookLoad.Loaded("book-1", snapshot(visibility = BookVisibility.Public))
                val page = BookDetailPage(load = null, overlay = restoring).withLoad(load)
                page.load shouldBe load
                page.overlay.isRestoringToAllBooks shouldBe false
                page.overlay.showShelfPicker shouldBe true
            }

            test("asking for the same book again keeps the visit and what is open") {
                BookDetailPage(load = null, overlay = restoring).visiting("book-1").overlay shouldBe restoring
            }

            test("asking for another book starts the next visit with nothing open, and keeps the load") {
                val load = BookLoad.Loaded("book-1", snapshot())
                val page = BookDetailPage(load = load, overlay = restoring).visiting("book-2")
                page.overlay shouldBe BookDetailOverlay("book-2", visit = 4)
                page.load shouldBe load
            }

            test("coming back to a book is a new visit, not the old one") {
                BookDetailPage(load = null, overlay = restoring).visiting("book-2").visiting("book-1").overlay shouldBe
                    BookDetailOverlay("book-1", visit = 5)
            }

            test("a vanished row clears the overlay but keeps the visit") {
                BookDetailPage(load = null, overlay = restoring).withLoad(BookLoad.Missing("book-1")).overlay shouldBe
                    BookDetailOverlay("book-1", visit = 3)
            }
        }

        context("toReady: the reading fields") {
            val four = TestData.chapters(count = 4, chapterDuration = 900_000L) // a 1 h book

            test("not started: Play, the first chapters, nothing in progress") {
                val ready = snapshot(chapters = four, position = null).toReady(BookDetailAmbient())
                ready.readingStage shouldBe ReadingStage.NotStarted
                ready.primaryAction shouldBe PrimaryAction.Play
                ready.currentChapter shouldBe null
                ready.timeRemainingMs shouldBe null
                ready.finishedSpan shouldBe null
                ready.chapterPreview.map { it.id } shouldBe four.map { it.id }
            }

            test("in progress: Resume, the current chapter, raw time left") {
                val ready = snapshot(chapters = four, position = position(1_000_000L)).toReady(BookDetailAmbient())
                ready.readingStage shouldBe ReadingStage.InProgress
                ready.primaryAction shouldBe PrimaryAction.Resume
                ready.currentChapter?.number shouldBe 2
                ready.currentChapter?.elapsedInChapterMs shouldBe 100_000L
                ready.timeRemainingMs shouldBe 2_600_000L
                ready.isComplete shouldBe false
            }

            test("finished: Listen again and the dates it was read between") {
                val finished = position(3_600_000L, finished = true).copy(startedAtMs = 1_000L, finishedAtMs = 9_000L)
                val ready = snapshot(chapters = four, position = finished).toReady(BookDetailAmbient())
                ready.readingStage shouldBe ReadingStage.Finished
                ready.primaryAction shouldBe PrimaryAction.ListenAgain
                ready.isComplete shouldBe true
                ready.finishedSpan shouldBe FinishedSpan(startedAtMs = 1_000L, finishedAtMs = 9_000L)
                ready.currentChapter shouldBe null
                ready.timeRemainingMs shouldBe null
            }

            test("a finished book with no recorded finish time shows no span rather than a made-up one") {
                snapshot(position = position(0L, finished = true)).toReady(BookDetailAmbient()).finishedSpan shouldBe null
            }

            test("unreachable with nothing on the device is Unavailable offline") {
                val offline = available.copy(showServerWarning = true, canPlay = false)
                snapshot(position = position(900_000L), availability = offline)
                    .toReady(BookDetailAmbient())
                    .primaryAction shouldBe PrimaryAction.UnavailableOffline
            }

            test("the download size is every audio file's size added up") {
                fun file(
                    id: String,
                    size: Long,
                ) = AudioFile(id = id, index = 0, filename = "$id.m4b", format = "m4b", codec = "aac", duration = 1_000L, size = size)
                val files = listOf(file("a", 1_000L), file("b", 2_500L))
                snapshot(detail = TestData.bookDetail(id = "book-1").copy(audioFiles = files))
                    .toReady(BookDetailAmbient())
                    .downloadSizeBytes shouldBe 3_500L
            }

            test("the scan warning shows to an admin only") {
                val flagged = snapshot(detail = TestData.bookDetail(id = "book-1").copy(hasScanWarning = true))
                flagged.toReady(BookDetailAmbient(isAdmin = false)).showScanWarning shouldBe false
                flagged.toReady(BookDetailAmbient(isAdmin = true)).showScanWarning shouldBe true
                flagged.toReady(BookDetailAmbient(isAdmin = false)).hasScanWarning shouldBe true // kept for today's UIs
            }

            test("an unlatched page orders sections by its live stage") {
                snapshot(chapters = four, position = position(900_000L))
                    .toReady(BookDetailAmbient())
                    .sectionOrder
                    .first() shouldBe BookDetailSection.Chapters
            }
        }

        context("the section-order latch") {
            val four = TestData.chapters(count = 4, chapterDuration = 900_000L)
            val inProgress = BookLoad.Loaded("book-1", snapshot(chapters = four, position = position(900_000L)))
            val finished = BookLoad.Loaded("book-1", snapshot(chapters = four, position = position(0L, finished = true)))
            val other = BookLoad.Loaded("book-2", snapshot(detail = TestData.bookDetail(id = "book-2")))
            val start = BookDetailPage(load = null, overlay = BookDetailOverlay(bookId = null))

            fun BookDetailPage.screen(id: String) =
                bookDetailUiState(id, load, BookDetailAmbient(), overlay, orderLatch).shouldBeInstanceOf<BookDetailUiState.Ready>()

            test("the first load of a visit latches its order") {
                val page = start.visiting("book-1").withLoad(inProgress)
                page.orderLatch?.run { order.first() } shouldBe BookDetailSection.Chapters
            }

            test("finishing during the visit changes the action, not the order") {
                val page = start.visiting("book-1").withLoad(inProgress).withLoad(finished)
                val ready = page.screen("book-1")
                ready.primaryAction shouldBe PrimaryAction.ListenAgain
                ready.sectionOrder.first() shouldBe BookDetailSection.Chapters
            }

            test("asking for the same book again keeps the latch (an iOS observer reusing its ViewModel)") {
                val page =
                    start
                        .visiting("book-1")
                        .withLoad(inProgress)
                        .withLoad(finished)
                        .visiting("book-1")
                page.screen("book-1").sectionOrder.first() shouldBe BookDetailSection.Chapters
            }

            test("a new visit to the book latches afresh: away and back shows the finished order") {
                val page =
                    start
                        .visiting("book-1")
                        .withLoad(inProgress)
                        .withLoad(finished)
                        .visiting("book-2")
                        .withLoad(other)
                        .visiting("book-1")
                        .withLoad(finished)
                page.screen("book-1").sectionOrder.first() shouldBe BookDetailSection.Ratings
            }

            test("coming straight back while the old load is still held latches from it at once") {
                val page =
                    start
                        .visiting("book-1")
                        .withLoad(inProgress)
                        .withLoad(finished)
                        .visiting("book-2") // book-2 never loads
                        .visiting("book-1")
                page.screen("book-1").sectionOrder.first() shouldBe BookDetailSection.Ratings
            }

            test("a vanished row does not disturb the latch") {
                val page =
                    start
                        .visiting("book-1")
                        .withLoad(inProgress)
                        .withLoad(BookLoad.Missing("book-1"))
                        .withLoad(finished)
                page.screen("book-1").sectionOrder.first() shouldBe BookDetailSection.Chapters
            }
        }
    })
