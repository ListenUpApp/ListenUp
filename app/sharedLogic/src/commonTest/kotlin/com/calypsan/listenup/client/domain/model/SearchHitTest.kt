package com.calypsan.listenup.client.domain.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/**
 * Tests for SearchHit domain model.
 *
 * Covers duration formatting with various edge cases.
 */
class SearchHitTest :
    FunSpec({

        fun createSearchHit(duration: Long? = null): SearchHit =
            SearchHit(
                id = "hit-1",
                type = SearchHitType.BOOK,
                name = "Test Book",
                duration = duration,
            )

        // ========== Duration Formatting Tests ==========

        test("formatDuration returns null when duration is null") {
            val hit = createSearchHit(duration = null)
            hit.formatDuration().shouldBeNull()
        }

        test("formatDuration returns hours and minutes for long duration") {
            val hit = createSearchHit(duration = 7_200_000L) // 2 hours
            hit.formatDuration() shouldBe "2h 0m"
        }

        test("formatDuration returns only minutes for short duration") {
            val hit = createSearchHit(duration = 1_800_000L) // 30 minutes
            hit.formatDuration() shouldBe "30m"
        }

        test("formatDuration handles mixed hours and minutes") {
            val hit = createSearchHit(duration = 5_400_000L) // 1.5 hours (90 minutes)
            hit.formatDuration() shouldBe "1h 30m"
        }

        test("formatDuration handles zero duration") {
            val hit = createSearchHit(duration = 0L)
            hit.formatDuration() shouldBe "0m"
        }

        test("formatDuration ignores seconds") {
            // 1 hour, 30 minutes, 30 seconds - should show 1h 30m
            val hit = createSearchHit(duration = 5_430_000L)
            hit.formatDuration() shouldBe "1h 30m"
        }
    })
