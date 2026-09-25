package com.calypsan.listenup.client.presentation.chaptereditor.timeline

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Dragging a boundary, and the one property that makes the gesture usable.
 *
 * A 65-hour book drawn whole is ~195 seconds per pixel, so precision cannot come from rendering —
 * it comes from pulling away to slow the drag down. The rule that makes that feel right is that
 * slowing down affects what happens *next* and never rewrites what already happened.
 */
class ScrubDragTest :
    FunSpec({
        // 1000px covering 100s: 100ms per pixel, so the arithmetic below is readable by eye.
        val geometry = TimelineGeometry(windowStartMs = 0L, windowEndMs = 100_000L, widthPx = 1_000f)
        val msPerPixel = geometry.msPerPixel

        fun drag() = ScrubDrag(chapterId = "c1", originalStartMs = 50_000L)

        test("with no pull the drag runs at the lane's own scale") {
            val moved = drag().advanced(dragPx = 10f, verticalDistanceDp = 0f, msPerPixel = msPerPixel)

            moved.step shouldBe FineScrubStep.Full
            moved.targetMs shouldBe 51_000L
        }

        test("pulling away slows the drag down") {
            val fine = drag().advanced(dragPx = 10f, verticalDistanceDp = 300f, msPerPixel = msPerPixel)

            fine.step shouldBe FineScrubStep.Fine
            // 10px * 100ms/px * 0.04 = 40ms, versus 1000ms unpulled.
            fine.targetMs shouldBe 50_040L
        }

        test("PULLING AWAY MID-DRAG DOES NOT REWIND WHAT ALREADY MOVED") {
            // The failure this guards against: recomputing from total displacement instead of
            // accumulating would yank the marker backwards the moment the user pulled away to be
            // careful — punishing the exact gesture the feature exists to reward.
            val coarse = drag().advanced(dragPx = 100f, verticalDistanceDp = 0f, msPerPixel = msPerPixel)
            coarse.targetMs shouldBe 60_000L

            val thenFine = coarse.advanced(dragPx = 10f, verticalDistanceDp = 300f, msPerPixel = msPerPixel)

            withClue("the coarse travel must be kept and only the new movement scaled") { thenFine.targetMs shouldBe 60_040L }
        }

        test("dragging backwards moves the boundary earlier") {
            val back = drag().advanced(dragPx = -25f, verticalDistanceDp = 0f, msPerPixel = msPerPixel)

            back.targetMs shouldBe 47_500L
        }

        test("shift is an explicit request for the finest step, whatever the pull") {
            val shifted =
                drag().advanced(dragPx = 10f, verticalDistanceDp = 0f, msPerPixel = msPerPixel, shiftHeld = true)

            shifted.step shouldBe FineScrubStep.Fine
        }

        test("pulling upward is the same as pulling downward") {
            // Which direction counts as "away" depends where the marker sits; a marker near the top of
            // the lane can only be pulled down. Sign must not decide whether the gesture works.
            val up = drag().advanced(dragPx = 10f, verticalDistanceDp = -300f, msPerPixel = msPerPixel)
            val down = drag().advanced(dragPx = 10f, verticalDistanceDp = 300f, msPerPixel = msPerPixel)

            up.targetMs shouldBe down.targetMs
        }

        val markers =
            listOf(
                TimelineChapter(id = "a", number = 1, startMs = 10_000L),
                TimelineChapter(id = "b", number = 2, startMs = 50_000L),
            )

        test("a press on a marker grabs it") {
            // 50_000ms sits at x = 500 on this geometry.
            chapterGrabbedAt(505f, markers, geometry)?.id shouldBe "b"
        }

        test("a press in open lane grabs nothing rather than the nearest boundary") {
            chapterGrabbedAt(300f, markers, geometry).shouldBeNull()
        }

        test("a locked boundary is still grabbable here, because the lane filters it, not the grab") {
            // Documents where the responsibility sits: chapterGrabbedAt answers "what is under the
            // finger", and the caller decides a locked boundary is not draggable. Splitting it the
            // other way would make the hit-test silently lie about what the user touched.
            val locked = listOf(TimelineChapter(id = "a", number = 1, startMs = 50_000L, locked = true))

            chapterGrabbedAt(500f, locked, geometry)?.id shouldBe "a"
        }

        test("between two markers the nearer one wins") {
            val close =
                listOf(
                    TimelineChapter(id = "a", number = 1, startMs = 50_000L),
                    TimelineChapter(id = "b", number = 2, startMs = 51_000L),
                )

            chapterGrabbedAt(508f, close, geometry)?.id shouldBe "b"
        }
    })
