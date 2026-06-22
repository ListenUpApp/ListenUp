package com.calypsan.listenup.client.features.documentviewer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [formatTimeLeft] and [scrubberPageIndex].
 *
 * Both functions are pure — no Android framework needed. Runs directly as a Kotest FunSpec.
 *
 * Coverage:
 * - [formatTimeLeft] — zero, sub-minute, round-up-to-minute, exact-hour, multi-hour
 * - [scrubberPageIndex] — zero fraction, full fraction (1.0), midpoint, negative clamp,
 *   over-1 clamp, zero-page-count guard
 */
class ReaderChromeFormattingTest :
    FunSpec({
        // ── formatTimeLeft ────────────────────────────────────────────────────

        test("0ms remaining shows 0m left") {
            formatTimeLeft(0L) shouldBe "0m left"
        }

        test("59s remaining shows 0m left (rounds down to 0 minutes)") {
            formatTimeLeft(59_000L) shouldBe "0m left"
        }

        test("90s remaining shows 1m left") {
            formatTimeLeft(90_000L) shouldBe "1m left"
        }

        test("exactly 1 hour remaining shows 1h 0m left") {
            formatTimeLeft(3_600_000L) shouldBe "1h 0m left"
        }

        test("9h 51m remaining shows 9h 51m left") {
            formatTimeLeft(35_460_000L) shouldBe "9h 51m left"
        }

        // ── scrubberPageIndex ─────────────────────────────────────────────────

        test("fraction 0 with 1232 pages yields index 0") {
            scrubberPageIndex(0f, 1232) shouldBe 0
        }

        test("fraction 1 with 1232 pages yields index 1231 (last page, 0-based)") {
            scrubberPageIndex(1f, 1232) shouldBe 1231
        }

        test("fraction 0.5 with 1000 pages yields index 499") {
            // round(0.5 * 1000) = 500 → 1-based page 500 → 0-based index 499
            scrubberPageIndex(0.5f, 1000) shouldBe 499
        }

        test("negative fraction is clamped to index 0") {
            scrubberPageIndex(-1f, 10) shouldBe 0
        }

        test("fraction > 1 is clamped to last index") {
            scrubberPageIndex(2f, 10) shouldBe 9
        }

        test("pageCount 0 yields index 0 (guard against divide-by-zero)") {
            scrubberPageIndex(0.5f, 0) shouldBe 0
        }
    })
