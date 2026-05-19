package com.calypsan.listenup.client.domain.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for Chapter domain model.
 *
 * Covers duration formatting in MM:SS format.
 */
class ChapterTest :
    FunSpec({

        fun createChapter(
            duration: Long = 60_000L, // 1 minute
        ): Chapter =
            Chapter(
                id = "chapter-1",
                title = "Chapter 1",
                duration = duration,
                startTime = 0L,
            )

        // ========== Duration Formatting Tests ==========

        test("formatDuration returns MM SS format") {
            val chapter = createChapter(duration = 125_000L) // 2 minutes 5 seconds
            chapter.formatDuration() shouldBe "02:05"
        }

        test("formatDuration pads single digit minutes") {
            val chapter = createChapter(duration = 300_000L) // 5 minutes
            chapter.formatDuration() shouldBe "05:00"
        }

        test("formatDuration pads single digit seconds") {
            val chapter = createChapter(duration = 603_000L) // 10 minutes 3 seconds
            chapter.formatDuration() shouldBe "10:03"
        }

        test("formatDuration handles zero duration") {
            val chapter = createChapter(duration = 0L)
            chapter.formatDuration() shouldBe "00:00"
        }

        test("formatDuration handles exactly one minute") {
            val chapter = createChapter(duration = 60_000L)
            chapter.formatDuration() shouldBe "01:00"
        }

        test("formatDuration handles long chapters") {
            val chapter = createChapter(duration = 3_661_000L) // 61 minutes 1 second
            chapter.formatDuration() shouldBe "61:01"
        }

        test("formatDuration handles very long chapters") {
            val chapter = createChapter(duration = 7_200_000L) // 120 minutes (2 hours)
            chapter.formatDuration() shouldBe "120:00"
        }

        test("formatDuration ignores milliseconds") {
            // 1 minute, 30 seconds, 500 milliseconds - should show 01:30
            val chapter = createChapter(duration = 90_500L)
            chapter.formatDuration() shouldBe "01:30"
        }
    })
