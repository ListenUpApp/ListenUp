package com.calypsan.listenup.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val TEN_HOURS_MS = 10L * 60 * 60 * 1000
private const val TWENTY_HOURS_MS = 20L * 60 * 60 * 1000

class BookProgressTest :
    FunSpec({
        test("no saved position is not started") {
            BookProgress.of(positionMs = null, durationMs = TEN_HOURS_MS, finishedFlag = false) shouldBe
                BookProgress.NotStarted
        }

        test("a position at zero is not started") {
            BookProgress.of(positionMs = 0L, durationMs = TEN_HOURS_MS, finishedFlag = false) shouldBe
                BookProgress.NotStarted
        }

        test("the finished flag is finished wherever the position is, or with none") {
            BookProgress.of(positionMs = null, durationMs = TEN_HOURS_MS, finishedFlag = true) shouldBe
                BookProgress.Finished
            BookProgress.of(positionMs = 0L, durationMs = TEN_HOURS_MS, finishedFlag = true) shouldBe
                BookProgress.Finished
            BookProgress.of(positionMs = 60_000L, durationMs = TEN_HOURS_MS, finishedFlag = true) shouldBe
                BookProgress.Finished
        }

        test("a position partway through is in progress, with its fraction, percent and time left") {
            val progress = BookProgress.of(positionMs = 4_000_000L, durationMs = TEN_HOURS_MS, finishedFlag = false)

            progress.shouldBeInstanceOf<BookProgress.InProgress>()
            progress.positionMs shouldBe 4_000_000L
            progress.durationMs shouldBe TEN_HOURS_MS
            progress.fraction shouldBe (4_000_000f / TEN_HOURS_MS plusOrMinus 0.0001f)
            progress.percent shouldBe 11
            progress.remainingMs shouldBe TEN_HOURS_MS - 4_000_000L
        }

        test("the percent floors rather than rounds: 39.6% reads 39") {
            val progress =
                BookProgress.of(positionMs = TEN_HOURS_MS * 396 / 1000, durationMs = TEN_HOURS_MS, finishedFlag = false)

            progress.shouldBeInstanceOf<BookProgress.InProgress>().percent shouldBe 39
        }

        test("a started book never reads 0%: 0.4% reads 1%") {
            val progress = BookProgress.of(positionMs = TEN_HOURS_MS * 4 / 1000, durationMs = TEN_HOURS_MS, finishedFlag = false)

            progress.shouldBeInstanceOf<BookProgress.InProgress>().percent shouldBe 1
        }

        test("a single millisecond in is started, at 1%") {
            BookProgress
                .of(positionMs = 1L, durationMs = TEN_HOURS_MS, finishedFlag = false)
                .shouldBeInstanceOf<BookProgress.InProgress>()
                .percent shouldBe 1
        }

        test("an unfinished book never reads 100%: 99.9% of a twenty-hour book, outside the credits, reads 99%") {
            // 0.1% of twenty hours is 72 s from the end: past the one-minute credits cap, so not finished.
            val progress =
                BookProgress.of(positionMs = TWENTY_HOURS_MS * 999 / 1000, durationMs = TWENTY_HOURS_MS, finishedFlag = false)

            val inProgress = progress.shouldBeInstanceOf<BookProgress.InProgress>()
            inProgress.percent shouldBe 99
            inProgress.remainingMs shouldBe 72_000L
        }

        test("99.9% of a ten-hour book is inside the end credits, so it is finished without the flag") {
            // 0.1% of ten hours is 36 s from the end: inside the one-minute credits.
            BookProgress.of(positionMs = TEN_HOURS_MS * 999 / 1000, durationMs = TEN_HOURS_MS, finishedFlag = false) shouldBe
                BookProgress.Finished
        }

        test("stopping thirty seconds from the end is finished: the same credits rule as FinishedPolicy") {
            BookProgress.of(positionMs = TEN_HOURS_MS - 30_000L, durationMs = TEN_HOURS_MS, finishedFlag = false) shouldBe
                BookProgress.Finished
        }

        test("a position past the end is finished") {
            BookProgress.of(positionMs = TEN_HOURS_MS + 5_000L, durationMs = TEN_HOURS_MS, finishedFlag = false) shouldBe
                BookProgress.Finished
        }

        test("an unknown duration cannot say how far along a book is, so a position alone is not started") {
            BookProgress.of(positionMs = 5_000L, durationMs = 0L, finishedFlag = false) shouldBe BookProgress.NotStarted
            BookProgress.of(positionMs = 5_000L, durationMs = -1L, finishedFlag = false) shouldBe BookProgress.NotStarted
        }

        test("an unknown duration with the flag is still finished") {
            BookProgress.of(positionMs = 5_000L, durationMs = 0L, finishedFlag = true) shouldBe BookProgress.Finished
        }

        test("a negative position is not started") {
            BookProgress.of(positionMs = -10L, durationMs = TEN_HOURS_MS, finishedFlag = false) shouldBe BookProgress.NotStarted
        }

        test("every in-progress percent sits in 1..99 and agrees with FinishedPolicy") {
            val duration = 100_000L
            (1L..duration step 7L).forEach { position ->
                val progress = BookProgress.of(positionMs = position, durationMs = duration, finishedFlag = false)
                val finished = FinishedPolicy.isFinished(position, duration, flag = false)
                (progress is BookProgress.Finished) shouldBe finished
                if (progress is BookProgress.InProgress) (progress.percent in 1..99) shouldBe true
            }
        }
    })
