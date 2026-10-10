package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.TestData
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
                bookDetailUiState(null, null, BookDetailAmbient(), BookDetailOverlay(null)) shouldBe BookDetailUiState.Loading
            }

            test("Loading while the latest load is another book's — never that book's page") {
                bookDetailUiState("book-2", loaded, BookDetailAmbient(), BookDetailOverlay("book-2")) shouldBe
                    BookDetailUiState.Loading
                bookDetailUiState("book-1", null, BookDetailAmbient(), BookDetailOverlay("book-1")) shouldBe
                    BookDetailUiState.Loading
            }

            test("a book with no row is NotFound") {
                bookDetailUiState("book-1", BookLoad.Missing("book-1"), BookDetailAmbient(), BookDetailOverlay("book-1"))
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
                    ).shouldBeInstanceOf<BookDetailUiState.Ready>()
                ready.showShelfPicker shouldBe true
                ready.shelfError shouldBe "Full."
            }

            test("an overlay left over from another book is ignored") {
                bookDetailUiState("book-1", loaded, BookDetailAmbient(), BookDetailOverlay("book-0", showShelfPicker = true))
                    .shouldBeInstanceOf<BookDetailUiState.Ready>()
                    .showShelfPicker shouldBe false
            }
        }

        context("withOverlay") {
            val inProgress = snapshot(position = position(900_000L)).toReady(BookDetailAmbient())

            test("Completed shows the book finished") {
                inProgress.withOverlay(BookDetailOverlay("book-1", progressOverride = ProgressOverride.Completed)).isComplete shouldBe true
            }

            test("Discarded clears progress and time left") {
                val ready = inProgress.withOverlay(BookDetailOverlay("book-1", progressOverride = ProgressOverride.Discarded))
                ready.isComplete shouldBe false
                ready.progress shouldBe null
                ready.timeRemainingFormatted shouldBe null
            }

            test("Restarted shows the start") {
                val ready = inProgress.withOverlay(BookDetailOverlay("book-1", progressOverride = ProgressOverride.Restarted))
                ready.isComplete shouldBe false
                ready.progress shouldBe 0f
            }

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
                    progressOverride = ProgressOverride.Completed,
                )

            test("Room speaking again retires the optimistic progress, and keeps everything the reader has open") {
                val after = busy.retiredBy(BookLoad.Loaded("book-1", snapshot(visibility = BookVisibility.Stranded)))
                after.progressOverride shouldBe null
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
    })
