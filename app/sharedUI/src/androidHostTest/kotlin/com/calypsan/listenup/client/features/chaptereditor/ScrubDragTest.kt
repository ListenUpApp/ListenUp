package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.design.timeline.FineScrubStep
import com.calypsan.listenup.client.design.timeline.TimelineChapter
import com.calypsan.listenup.client.design.timeline.TimelineGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Dragging a boundary, and the one property that makes the gesture usable.
 *
 * A 65-hour book drawn whole is ~195 seconds per pixel, so precision cannot come from rendering —
 * it comes from pulling away to slow the drag down. The rule that makes that feel right is that
 * slowing down affects what happens *next* and never rewrites what already happened.
 */
class ScrubDragTest {
    // 1000px covering 100s: 100ms per pixel, so the arithmetic below is readable by eye.
    private val geometry = TimelineGeometry(windowStartMs = 0L, windowEndMs = 100_000L, widthPx = 1_000f)
    private val msPerPixel = geometry.msPerPixel

    private fun drag() = ScrubDrag(chapterId = "c1", originalStartMs = 50_000L)

    @Test
    fun `with no pull the drag runs at the lane's own scale`() {
        val moved = drag().advanced(dragPx = 10f, verticalDistanceDp = 0f, msPerPixel = msPerPixel)

        assertEquals(FineScrubStep.Full, moved.step)
        assertEquals(51_000L, moved.targetMs)
    }

    @Test
    fun `pulling away slows the drag down`() {
        val fine = drag().advanced(dragPx = 10f, verticalDistanceDp = 300f, msPerPixel = msPerPixel)

        assertEquals(FineScrubStep.Fine, fine.step)
        // 10px * 100ms/px * 0.04 = 40ms, versus 1000ms unpulled.
        assertEquals(50_040L, fine.targetMs)
    }

    @Test
    fun `PULLING AWAY MID-DRAG DOES NOT REWIND WHAT ALREADY MOVED`() {
        // The failure this guards against: recomputing from total displacement instead of
        // accumulating would yank the marker backwards the moment the user pulled away to be
        // careful — punishing the exact gesture the feature exists to reward.
        val coarse = drag().advanced(dragPx = 100f, verticalDistanceDp = 0f, msPerPixel = msPerPixel)
        assertEquals(60_000L, coarse.targetMs)

        val thenFine = coarse.advanced(dragPx = 10f, verticalDistanceDp = 300f, msPerPixel = msPerPixel)

        assertEquals(
            "the coarse travel must be kept and only the new movement scaled",
            60_040L,
            thenFine.targetMs,
        )
    }

    @Test
    fun `dragging backwards moves the boundary earlier`() {
        val back = drag().advanced(dragPx = -25f, verticalDistanceDp = 0f, msPerPixel = msPerPixel)

        assertEquals(47_500L, back.targetMs)
    }

    @Test
    fun `shift is an explicit request for the finest step, whatever the pull`() {
        val shifted =
            drag().advanced(dragPx = 10f, verticalDistanceDp = 0f, msPerPixel = msPerPixel, shiftHeld = true)

        assertEquals(FineScrubStep.Fine, shifted.step)
    }

    @Test
    fun `pulling upward is the same as pulling downward`() {
        // Which direction counts as "away" depends where the marker sits; a marker near the top of
        // the lane can only be pulled down. Sign must not decide whether the gesture works.
        val up = drag().advanced(dragPx = 10f, verticalDistanceDp = -300f, msPerPixel = msPerPixel)
        val down = drag().advanced(dragPx = 10f, verticalDistanceDp = 300f, msPerPixel = msPerPixel)

        assertEquals(down.targetMs, up.targetMs)
    }

    private val markers =
        listOf(
            TimelineChapter(id = "a", number = 1, startMs = 10_000L),
            TimelineChapter(id = "b", number = 2, startMs = 50_000L),
        )

    @Test
    fun `a press on a marker grabs it`() {
        // 50_000ms sits at x = 500 on this geometry.
        assertEquals("b", chapterGrabbedAt(505f, markers, geometry)?.id)
    }

    @Test
    fun `a press in open lane grabs nothing rather than the nearest boundary`() {
        assertNull(chapterGrabbedAt(300f, markers, geometry))
    }

    @Test
    fun `a locked boundary is still grabbable here, because the lane filters it, not the grab`() {
        // Documents where the responsibility sits: chapterGrabbedAt answers "what is under the
        // finger", and the caller decides a locked boundary is not draggable. Splitting it the
        // other way would make the hit-test silently lie about what the user touched.
        val locked = listOf(TimelineChapter(id = "a", number = 1, startMs = 50_000L, locked = true))

        assertEquals("a", chapterGrabbedAt(500f, locked, geometry)?.id)
    }

    @Test
    fun `between two markers the nearer one wins`() {
        val close =
            listOf(
                TimelineChapter(id = "a", number = 1, startMs = 50_000L),
                TimelineChapter(id = "b", number = 2, startMs = 51_000L),
            )

        assertEquals("b", chapterGrabbedAt(508f, close, geometry)?.id)
    }
}
