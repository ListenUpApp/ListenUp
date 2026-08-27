package com.calypsan.listenup.client.design.timeline

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe

/** Wind and Truth: 65 hours, the book the spec sizes the editor against. */
private const val BOOK_MS = 234_000_000L

/**
 * [TimelineGeometry] — the millisecond↔pixel mapping the whole editor is drawn through.
 *
 * This is the piece where a 65-hour book breaks naive code. At full-book zoom one pixel is roughly
 * 200 seconds, so *every* visual affordance is useless for precision and the arithmetic has to stay
 * honest at both extremes at once: a window 10 seconds wide, and a book 234 million milliseconds
 * long. Pixels are `Float` and time is `Long`, so the conversions cannot simply round-trip — the
 * tests below pin how much they may lose, rather than pretending they lose nothing.
 */
class TimelineGeometryTest :
    FunSpec({

        test("a window maps its own edges to the ends of the lane") {
            val g = TimelineGeometry(windowStartMs = 0L, windowEndMs = 60_000L, widthPx = 1000f)

            g.xOf(0L) shouldBe 0f
            g.xOf(60_000L) shouldBe 1000f
            withClue("the midpoint of the window is the midpoint of the lane") {
                g.xOf(30_000L) shouldBe 500f
            }
        }

        test("a position outside the window maps outside the lane rather than clamping") {
            // Markers just off-screen still need a coordinate: the lane clips them, but a clamped
            // x would pile every distant chapter onto the two edges and draw a false crowd there.
            val g = TimelineGeometry(windowStartMs = 60_000L, windowEndMs = 120_000L, widthPx = 600f)

            g.xOf(30_000L) shouldBeLessThan 0f
            g.xOf(180_000L) shouldBeGreaterThan 600f
        }

        test("x and time round-trip within one pixel of time") {
            // A pixel of a 30-minute window is ~1.8s, so equality is the wrong assertion; the
            // honest one is that the error stays under what a single pixel can express.
            val g = TimelineGeometry(windowStartMs = 147_428_000L, windowEndMs = 149_228_000L, widthPx = 980f)
            val msPerPx = (149_228_000L - 147_428_000L) / 980.0

            listOf(147_428_000L, 148_328_420L, 149_227_999L).forEach { ms ->
                val round = g.msOf(g.xOf(ms))
                withClue("$ms round-tripped to $round") {
                    kotlin.math.abs(round - ms).toDouble() shouldBeLessThan msPerPx
                }
            }
        }

        test("the full-book window is for navigation, not precision — and says so") {
            // The spec's own reasoning: ~200 s/px at full zoom. Anything that asks the geometry
            // for precision here should be able to find out that it cannot have it.
            val g = TimelineGeometry(windowStartMs = 0L, windowEndMs = BOOK_MS, widthPx = 1200f)

            withClue("195 seconds per pixel on a 65-hour book at 1200px") {
                (g.msPerPixel / 1000.0) shouldBeGreaterThan 190.0
            }
        }

        test("a ten-second window still resolves single milliseconds") {
            val g = TimelineGeometry(windowStartMs = 148_320_000L, windowEndMs = 148_330_000L, widthPx = 1000f)

            withClue("10s over 1000px is 10ms per pixel — the deepest zoom the spec asks for") {
                g.msPerPixel shouldBe 10.0
            }
        }

        test("a zero-width lane does not divide by zero") {
            // Compose measures at 0 on the first frame; the geometry must survive that pass
            // rather than emit NaN into every marker position.
            val g = TimelineGeometry(windowStartMs = 0L, windowEndMs = 60_000L, widthPx = 0f)

            g.xOf(30_000L).isNaN() shouldBe false
            g.msOf(0f) shouldBe 0L
        }

        test("a zero-length window does not divide by zero") {
            val g = TimelineGeometry(windowStartMs = 1_000L, windowEndMs = 1_000L, widthPx = 500f)

            g.xOf(1_000L).isNaN() shouldBe false
            g.msOf(250f) shouldBe 1_000L
        }

        test("panning keeps the window's length and cannot leave the book") {
            val g = TimelineGeometry(windowStartMs = 0L, windowEndMs = 60_000L, widthPx = 1000f)

            val panned = g.panBy(deltaPx = -500f, bookDurationMs = BOOK_MS)
            panned.windowStartMs shouldBe 30_000L
            panned.windowLengthMs shouldBe 60_000L

            withClue("panning before the start of the book stops at the start") {
                g.panBy(deltaPx = 5000f, bookDurationMs = BOOK_MS).windowStartMs shouldBe 0L
            }
            withClue("panning past the end stops with the window's tail at the end") {
                val far = TimelineGeometry(BOOK_MS - 60_000L, BOOK_MS, 1000f)
                far.panBy(deltaPx = -5000f, bookDurationMs = BOOK_MS).windowEndMs shouldBe BOOK_MS
            }
        }

        test("zooming holds the anchored instant still under the cursor") {
            // Scroll-to-zoom is unusable if the thing under the pointer drifts away from it.
            val g = TimelineGeometry(windowStartMs = 0L, windowEndMs = 60_000L, widthPx = 1000f)
            val anchorX = 250f
            val anchorMs = g.msOf(anchorX)

            val zoomed = g.zoomBy(factor = 0.5f, focusPx = anchorX, bookDurationMs = BOOK_MS)

            withClue("the instant under the cursor stays under the cursor") {
                kotlin.math.abs(zoomed.msOf(anchorX) - anchorMs) shouldBeLessThan zoomed.msPerPixel.toLong() + 1
            }
            zoomed.windowLengthMs shouldBe 30_000L
        }

        test("zoom stops at ten seconds in and the whole book out") {
            val g = TimelineGeometry(windowStartMs = 0L, windowEndMs = 60_000L, widthPx = 1000f)

            val tooDeep = g.zoomBy(factor = 0.0001f, focusPx = 500f, bookDurationMs = BOOK_MS)
            withClue("the spec's floor is a ~10s window") { tooDeep.windowLengthMs shouldBe 10_000L }

            val tooWide = g.zoomBy(factor = 10_000f, focusPx = 500f, bookDurationMs = BOOK_MS)
            withClue("no useful view is wider than the book itself") {
                tooWide.windowLengthMs shouldBe BOOK_MS
            }
        }
    })
