package com.calypsan.listenup.client.presentation.library

import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LibraryReadingStateTest :
    FunSpec({
        fun book(
            id: String,
            durationMs: Long = 10_000L,
        ) = BookListItem(
            id = BookId(id),
            libraryId = LibraryId("lib"),
            folderId = FolderId("folder"),
            title = "Book $id",
            authors = emptyList(),
            narrators = emptyList(),
            duration = durationMs,
            coverPath = null,
            addedAt = Timestamp(0L),
            updatedAt = Timestamp(0L),
        )

        fun position(
            id: String,
            positionMs: Long,
            isFinished: Boolean = false,
        ) = PlaybackPosition(
            bookId = id,
            positionMs = positionMs,
            playbackSpeed = 1f,
            hasCustomSpeed = false,
            volumeBoostDb = 0f,
            hasCustomBoost = false,
            measuredGainDb = null,
            updatedAtMs = 0L,
            syncedAtMs = null,
            lastPlayedAtMs = null,
            isFinished = isFinished,
        )

        test("no position, or a position at zero, is not started") {
            readingStateOf(null) shouldBe ReadingState.NOT_STARTED
            readingStateOf(position("b", 0L)) shouldBe ReadingState.NOT_STARTED
        }

        test("any progress short of finished is in progress") {
            readingStateOf(position("b", 1L)) shouldBe ReadingState.IN_PROGRESS
        }

        test("isFinished wins over a position, including a position at zero after Listen again") {
            readingStateOf(position("b", 0L, isFinished = true)) shouldBe ReadingState.FINISHED
            readingStateOf(position("b", 4_000L, isFinished = true)) shouldBe ReadingState.FINISHED
        }

        test("counts cover the whole library") {
            val states =
                mapOf(
                    BookId("1") to ReadingState.IN_PROGRESS,
                    BookId("2") to ReadingState.FINISHED,
                    BookId("3") to ReadingState.NOT_STARTED,
                    BookId("4") to ReadingState.NOT_STARTED,
                )
            countsOf(states.values) shouldBe BookStatusCounts(all = 4, inProgress = 1, notStarted = 2, finished = 1)
        }

        test("each filter admits exactly its own state, and All admits every state") {
            ReadingState.entries.forEach { BookStatusFilter.ALL.admits(it) shouldBe true }
            BookStatusFilter.IN_PROGRESS.admits(ReadingState.IN_PROGRESS) shouldBe true
            BookStatusFilter.IN_PROGRESS.admits(ReadingState.FINISHED) shouldBe false
            BookStatusFilter.NOT_STARTED.admits(ReadingState.NOT_STARTED) shouldBe true
            BookStatusFilter.FINISHED.admits(ReadingState.FINISHED) shouldBe true
            BookStatusFilter.FINISHED.admits(ReadingState.NOT_STARTED) shouldBe false
        }

        test("card status carries time left as duration minus position") {
            cardStatusOf(book("b", durationMs = 10_000L), position("b", 4_000L)) shouldBe
                BookCardStatus.InProgress(fraction = 0.4f, timeLeftMs = 6_000L)
        }

        test("card status for finished and not started carries the length") {
            cardStatusOf(book("b", durationMs = 10_000L), position("b", 10_000L, isFinished = true)) shouldBe
                BookCardStatus.Finished(durationMs = 10_000L)
            cardStatusOf(book("b", durationMs = 10_000L), null) shouldBe BookCardStatus.NotStarted(durationMs = 10_000L)
        }

        test("a position past the end clamps: no negative time left, fraction at most 1") {
            cardStatusOf(book("b", durationMs = 10_000L), position("b", 12_000L)) shouldBe
                BookCardStatus.InProgress(fraction = 1f, timeLeftMs = 0L)
        }

        test("a book with no known duration in progress reports zero fraction, not a division by zero") {
            cardStatusOf(book("b", durationMs = 0L), position("b", 5_000L)) shouldBe
                BookCardStatus.InProgress(fraction = 0f, timeLeftMs = 0L)
        }

        test("countFor reads the matching count") {
            val counts = BookStatusCounts(all = 248, inProgress = 4, notStarted = 183, finished = 61)
            counts.countFor(BookStatusFilter.ALL) shouldBe 248
            counts.countFor(BookStatusFilter.IN_PROGRESS) shouldBe 4
            counts.countFor(BookStatusFilter.NOT_STARTED) shouldBe 183
            counts.countFor(BookStatusFilter.FINISHED) shouldBe 61
        }
    })
