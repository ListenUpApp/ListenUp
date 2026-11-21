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
            readingStateOf(null, durationMs = 10_000L) shouldBe ReadingState.NOT_STARTED
            readingStateOf(position("b", 0L), durationMs = 10_000L) shouldBe ReadingState.NOT_STARTED
        }

        test("any progress short of finished is in progress") {
            readingStateOf(position("b", 1L), durationMs = 10_000L) shouldBe ReadingState.IN_PROGRESS
        }

        test("isFinished wins over a position, including a position at zero after Listen again") {
            readingStateOf(position("b", 0L, isFinished = true), durationMs = 10_000L) shouldBe ReadingState.FINISHED
            readingStateOf(position("b", 4_000L, isFinished = true), durationMs = 10_000L) shouldBe ReadingState.FINISHED
        }

        test("a position inside the end credits is finished without the flag, so the filters agree with the card") {
            val tenHours = 36_000_000L
            readingStateOf(position("b", tenHours - 30_000L), durationMs = tenHours) shouldBe ReadingState.FINISHED
            cardStatusOf(book("b", durationMs = tenHours), position("b", tenHours - 30_000L)) shouldBe
                BookCardStatus.Finished(durationMs = tenHours)
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

        test("card status carries the one percent and time left") {
            cardStatusOf(book("b", durationMs = 10_000L), position("b", 4_000L)) shouldBe
                BookCardStatus.InProgress(fraction = 0.4f, percent = 40, timeLeftMs = 6_000L)
        }

        test("card status percent floors, and a started book never reads 0%") {
            val tenHours = 36_000_000L
            // 39.6%.
            (cardStatusOf(book("b", durationMs = tenHours), position("b", 14_256_000L)) as BookCardStatus.InProgress)
                .percent shouldBe 39
            // 0.4%.
            (cardStatusOf(book("b", durationMs = tenHours), position("b", 144_000L)) as BookCardStatus.InProgress)
                .percent shouldBe 1
        }

        test("card status for finished and not started carries the length") {
            cardStatusOf(book("b", durationMs = 10_000L), position("b", 10_000L, isFinished = true)) shouldBe
                BookCardStatus.Finished(durationMs = 10_000L)
            cardStatusOf(book("b", durationMs = 10_000L), null) shouldBe BookCardStatus.NotStarted(durationMs = 10_000L)
        }

        test("a position past the end is finished") {
            cardStatusOf(book("b", durationMs = 10_000L), position("b", 12_000L)) shouldBe
                BookCardStatus.Finished(durationMs = 10_000L)
        }

        test("a book with no known duration cannot say how far along it is, so it reads not started") {
            cardStatusOf(book("b", durationMs = 0L), position("b", 5_000L)) shouldBe BookCardStatus.NotStarted(durationMs = 0L)
            readingStateOf(position("b", 5_000L), durationMs = 0L) shouldBe ReadingState.NOT_STARTED
        }

        test("countFor reads the matching count") {
            val counts = BookStatusCounts(all = 248, inProgress = 4, notStarted = 183, finished = 61)
            counts.countFor(BookStatusFilter.ALL) shouldBe 248
            counts.countFor(BookStatusFilter.IN_PROGRESS) shouldBe 4
            counts.countFor(BookStatusFilter.NOT_STARTED) shouldBe 183
            counts.countFor(BookStatusFilter.FINISHED) shouldBe 61
        }
    })
