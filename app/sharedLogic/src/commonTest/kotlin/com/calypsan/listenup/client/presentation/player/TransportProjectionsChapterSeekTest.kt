package com.calypsan.listenup.client.presentation.player

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TransportProjectionsChapterSeekTest :
    FunSpec({
        val chapter =
            PlayerChapter(
                index = 5,
                number = 6,
                total = 75,
                title = "Bridge Four",
                isGenericTitle = false,
                isWholeBook = false,
                startMs = 1_000_000,
                durationMs = 600_000,
            )

        test("an offset inside the chapter lands at its start plus the offset") {
            bookPositionInChapterMs(chapter, offsetMs = 30_000) shouldBe 1_030_000
        }

        test("a negative offset clamps to the chapter's start") {
            bookPositionInChapterMs(chapter, offsetMs = -5_000) shouldBe 1_000_000
        }

        test("an offset past the chapter clamps to its end") {
            bookPositionInChapterMs(chapter, offsetMs = 900_000) shouldBe 1_600_000
        }

        test("a whole-book chapter maps one to one") {
            val wholeBook =
                chapter.copy(index = 0, number = 1, total = 1, isWholeBook = true, startMs = 0, durationMs = 9_000_000)
            bookPositionInChapterMs(wholeBook, offsetMs = 4_321_000) shouldBe 4_321_000
        }
    })
