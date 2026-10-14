package com.calypsan.listenup.client.core.image

import com.calypsan.listenup.api.dto.image.ImageFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.checkAll

private fun finite(
    min: Double,
    max: Double,
) = Arb.double(min, max).filter { it.isFinite() }

/** Realistic photos: no side under 100 px, so no aspect ratio past 40:1. */
private val photoSide = finite(100.0, 4_000.0)
private val editorSide = finite(10.0, 2_000.0)
private val validFrame = Arb.bind(finite(0.0, 1.0), finite(0.0, 1.0), finite(1.0, 4.0)) { x, y, z -> ImageFrame(x, y, z) }
private val delta = finite(-50.0, 50.0)
private val anchor = Arb.bind(finite(0.0, 1.0), finite(0.0, 1.0)) { x, y -> x to y }

private infix fun ImageFrame.shouldBeCloseTo(expected: ImageFrame) {
    focusX shouldBe (expected.focusX plusOrMinus 1e-8)
    focusY shouldBe (expected.focusY plusOrMinus 1e-8)
    zoom shouldBe (expected.zoom plusOrMinus 1e-9)
}

class FrameEditingTest :
    FunSpec({
        test("P5: a pan and its reverse give back the frame when neither hit an edge") {
            checkAll(photoSide, photoSide, editorSide, validFrame, delta, delta) { iw, ih, v, f, dx, dy ->
                val start = f.canonical(iw, ih)
                val there = panFrame(start, iw, ih, v, dx, dy)
                val back = panFrame(there.frame, iw, ih, v, -dx, -dy)
                if (!there.hitEdge && !back.hitEdge) back.frame shouldBeCloseTo start
            }
        }

        test("P6: a zoom keeps the point under its anchor fixed unless a clamp stopped it") {
            checkAll(photoSide, photoSide, editorSide, validFrame, finite(0.5, 2.0), anchor) { iw, ih, v, f, factor, (ax, ay) ->
                val start = f.canonical(iw, ih)
                val before = frameLayout(iw, ih, v, v, start)
                val edit = zoomFrameBy(start, iw, ih, v, factor, ax * v, ay * v)
                if (!edit.hitEdge && !edit.hitZoomLimit) {
                    val after = frameLayout(iw, ih, v, v, edit.frame)
                    ((ax * v - after.offsetX) / after.width) shouldBe ((ax * v - before.offsetX) / before.width plusOrMinus 1e-8)
                    ((ay * v - after.offsetY) / after.height) shouldBe ((ay * v - before.offsetY) / before.height plusOrMinus 1e-8)
                }
            }
        }

        test("every edit returns a frame that is already canonical and finite") {
            checkAll(photoSide, photoSide, editorSide, validFrame, delta, delta) { iw, ih, v, f, dx, dy ->
                val edited = panFrame(f.canonical(iw, ih), iw, ih, v, dx, dy).frame
                edited.focusX.isFinite() shouldBe true
                edited.focusY.isFinite() shouldBe true
                edited.canonical(iw, ih) shouldBeCloseTo edited
            }
        }

        test("a pan past an edge is stopped there, and says so") {
            val square = ImageFrame(0.5, 0.5, 2.0)
            val edit = panFrame(square, 1_000.0, 1_000.0, 100.0, dx = 500.0, dy = 0.0)
            edit.hitEdge shouldBe true
            edit.frame.focusX shouldBe (0.25 plusOrMinus 1e-12) // left edge flush: the centre shows a quarter in
            panFrame(square, 1_000.0, 1_000.0, 100.0, dx = 10.0, dy = 0.0).hitEdge shouldBe false
        }

        test("zooming past either end stops at the limit, and says so") {
            val tooFar = zoomFrameTo(ImageFrame.Centered, 1_000.0, 1_000.0, 100.0, 5.0)
            tooFar.frame.zoom shouldBe ImageFrame.MAX_ZOOM
            tooFar.hitZoomLimit shouldBe true
            val tooNear = zoomFrameBy(ImageFrame.Centered, 1_000.0, 1_000.0, 100.0, 0.5, 50.0, 50.0)
            tooNear.frame.zoom shouldBe ImageFrame.MIN_ZOOM
            tooNear.hitZoomLimit shouldBe true
            val exact = zoomFrameTo(ImageFrame(0.5, 0.5, 3.9), 1_000.0, 1_000.0, 100.0, 4.0)
            exact.frame.zoom shouldBe (4.0 plusOrMinus 1e-12)
            exact.hitZoomLimit shouldBe false
        }

        test("nudging Up shows more of the photo's top; Left shows more of its left (decided call)") {
            val zoomed = ImageFrame(0.5, 0.5, 2.0)
            nudgeFrame(zoomed, 1_000.0, 1_000.0, 100.0, NudgeDirection.Up, large = false).frame.focusY shouldBeLessThan 0.5
            nudgeFrame(zoomed, 1_000.0, 1_000.0, 100.0, NudgeDirection.Down, large = false).frame.focusY shouldBeGreaterThan 0.5
            nudgeFrame(zoomed, 1_000.0, 1_000.0, 100.0, NudgeDirection.Left, large = false).frame.focusX shouldBeLessThan 0.5
            nudgeFrame(zoomed, 1_000.0, 1_000.0, 100.0, NudgeDirection.Right, large = false).frame.focusX shouldBeGreaterThan 0.5
        }

        test("a large nudge moves four times as far as a small one") {
            val zoomed = ImageFrame(0.5, 0.5, 4.0)
            val small = 0.5 - nudgeFrame(zoomed, 1_000.0, 1_000.0, 100.0, NudgeDirection.Up, large = false).frame.focusY
            val large = 0.5 - nudgeFrame(zoomed, 1_000.0, 1_000.0, 100.0, NudgeDirection.Up, large = true).frame.focusY
            large shouldBe (4 * small plusOrMinus 1e-12)
        }

        test("the wheel zooms in for a negative delta, and the keyboard step is 1.15") {
            wheelZoomFactor(0.0) shouldBe 1.0
            wheelZoomFactor(-100.0) shouldBeGreaterThan 1.0
            wheelZoomFactor(100.0) shouldBeLessThan 1.0
            ZOOM_STEP shouldBe 1.15
        }

        test("an edit with no viewport yet, or a NaN gesture, changes nothing") {
            val f = ImageFrame(0.4, 0.4, 2.0)
            panFrame(f, 1_000.0, 1_000.0, 0.0, 10.0, 10.0).frame shouldBe f
            panFrame(f, 1_000.0, 1_000.0, 100.0, Double.NaN, 0.0).frame shouldBe f
            zoomFrameBy(f, 1_000.0, 1_000.0, 100.0, Double.NaN, 50.0, 50.0).frame shouldBe f
        }
    })
