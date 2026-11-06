package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.Chapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ChapterWindowTest :
    FunSpec({
        val chapters =
            listOf(
                Chapter(id = "c0", title = "Prologue", duration = 60_000, startTime = 0),
                Chapter(id = "c1", title = "Chapter 1", duration = 60_000, startTime = 60_000),
                Chapter(id = "c2", title = "Bridge Four", duration = 80_000, startTime = 120_000),
            )

        test("no chapters presents the whole book as one window") {
            val window = chapterWindowAt(positionMs = 5_000, chapters = emptyList(), totalDurationMs = 200_000)
            window.index shouldBe -1
            window.startMs shouldBe 0
            window.durationMs shouldBe 200_000
            window.positionInWindowMs shouldBe 5_000
            window.isBeforeFirstChapter shouldBe false
        }

        test("the window of the chapter that contains the playhead") {
            val window = chapterWindowAt(positionMs = 70_000, chapters = chapters, totalDurationMs = 200_000)
            window.index shouldBe 1
            window.startMs shouldBe 60_000
            window.endMs shouldBe 120_000
            window.durationMs shouldBe 60_000
            window.positionInWindowMs shouldBe 10_000
            window.remainingMs shouldBe 50_000
            window.isBeforeFirstChapter shouldBe false
        }

        test("a boundary belongs to the chapter that starts there") {
            chapterWindowAt(positionMs = 60_000, chapters = chapters, totalDurationMs = 200_000).index shouldBe 1
        }

        test("the last chapter ends at the book's total") {
            chapterWindowAt(positionMs = 130_000, chapters = chapters, totalDurationMs = 200_000).endMs shouldBe 200_000
        }

        test("an unknown total ends the last chapter at its own start") {
            val window = chapterWindowAt(positionMs = 130_000, chapters = chapters, totalDurationMs = null)
            window.endMs shouldBe 120_000
            window.durationMs shouldBe 0
        }

        test("before the first chapter reads as chapter 0 and says so") {
            val late = listOf(Chapter(id = "a", title = "A", duration = 10, startTime = 30_000))
            val window = chapterWindowAt(positionMs = 10_000, chapters = late, totalDurationMs = 100_000)
            window.index shouldBe 0
            window.isBeforeFirstChapter shouldBe true
            window.positionInWindowMs shouldBe 0
        }

        test("a position past the end clamps to the window") {
            val window = chapterWindowAt(positionMs = 500_000, chapters = chapters, totalDurationMs = 200_000)
            window.index shouldBe 2
            window.positionInWindowMs shouldBe 80_000
            window.remainingMs shouldBe 0
        }

        test("bookPositionAt clamps the offset to the window") {
            val window = chapterWindowAt(positionMs = 70_000, chapters = chapters, totalDurationMs = 200_000)
            window.bookPositionAt(10_000) shouldBe 70_000
            window.bookPositionAt(-5_000) shouldBe 60_000
            window.bookPositionAt(999_999) shouldBe 120_000
        }

        test("bookPositionAtFraction clamps the fraction to 0..1") {
            val window = chapterWindowAt(positionMs = 70_000, chapters = chapters, totalDurationMs = 200_000)
            window.bookPositionAtFraction(0.5f) shouldBe 90_000
            window.bookPositionAtFraction(-1f) shouldBe 60_000
            window.bookPositionAtFraction(2f) shouldBe 120_000
        }
    })
