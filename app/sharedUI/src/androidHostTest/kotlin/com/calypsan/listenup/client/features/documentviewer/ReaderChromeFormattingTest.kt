package com.calypsan.listenup.client.features.documentviewer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [scrubberPageIndex].
 *
 * Pure — no Android framework needed. Runs directly as a Kotest FunSpec.
 *
 * Coverage:
 * - [scrubberPageIndex] — zero fraction, full fraction (1.0), midpoint, negative clamp,
 *   over-1 clamp, zero-page-count guard
 *
 * Time-remaining formatting for the reader's now-playing strip routes through the canonical
 * `DurationFormatter.timeLeft()` (see `DurationFormatterTest`) — the former hand-rolled
 * `formatTimeLeft` here was removed as a duplicate.
 */
class ReaderChromeFormattingTest :
    FunSpec({
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
