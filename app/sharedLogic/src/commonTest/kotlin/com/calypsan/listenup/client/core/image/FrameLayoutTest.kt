package com.calypsan.listenup.client.core.image

import com.calypsan.listenup.api.dto.image.ImageFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.checkAll
import kotlin.math.abs
import kotlin.math.max

/** Kotest's bounded Arb.double still edge-cases NaN and infinities in; sizes never are. */
private fun finite(
    min: Double,
    max: Double,
) = Arb.double(min, max).filter { it.isFinite() }

private val imageSide = finite(1.0, 10_000.0)
private val viewportSide = finite(1.0, 4_000.0)
private val focus = Arb.choice(finite(-1.0, 2.0), Arb.constant(Double.NaN))
private val zoom = Arb.choice(finite(0.0, 10.0), Arb.constant(Double.NaN))

/** Any frame at all, in range or not, NaN included: layout must clamp, never throw. */
internal val anyFrame = Arb.bind(focus, focus, zoom) { x, y, z -> ImageFrame(x, y, z) }

/** Relative tolerance for a quantity of size [scale]. */
internal fun tol(scale: Double) = 1e-9 * max(1.0, abs(scale))

private fun Double.sanitisedFocus() = if (isNaN()) 0.5 else coerceIn(0.0, 1.0)

class FrameLayoutTest :
    FunSpec({
        test("P1: the drawn image always covers the viewport") {
            checkAll(imageSide, imageSide, viewportSide, viewportSide, anyFrame) { iw, ih, vw, vh, f ->
                val l = frameLayout(iw, ih, vw, vh, f)
                val e = tol(max(l.width, l.height))
                l.offsetX shouldBeLessThanOrEqual e
                l.offsetY shouldBeLessThanOrEqual e
                l.offsetX + l.width shouldBeGreaterThanOrEqual vw - e
                l.offsetY + l.height shouldBeGreaterThanOrEqual vh - e
            }
        }

        test("P2: the focus point sits at the centre, unless the nearer edge is flush") {
            checkAll(imageSide, imageSide, viewportSide, viewportSide, anyFrame) { iw, ih, vw, vh, f ->
                val l = frameLayout(iw, ih, vw, vh, f)
                val ex = tol(l.width)
                val ey = tol(l.height)
                val px = f.focusX.sanitisedFocus() * l.width
                val py = f.focusY.sanitisedFocus() * l.height
                if (px >= vw / 2 && px <= l.width - vw / 2) {
                    l.offsetX + px shouldBe (vw / 2 plusOrMinus ex)
                } else {
                    (abs(l.offsetX) <= ex || abs(l.offsetX - (vw - l.width)) <= ex) shouldBe true
                }
                if (py >= vh / 2 && py <= l.height - vh / 2) {
                    l.offsetY + py shouldBe (vh / 2 plusOrMinus ey)
                } else {
                    (abs(l.offsetY) <= ey || abs(l.offsetY - (vh - l.height)) <= ey) shouldBe true
                }
            }
        }

        test("P3: no frame is exactly today's centre crop") {
            checkAll(imageSide, imageSide, viewportSide, viewportSide) { iw, ih, vw, vh ->
                val none = frameLayout(iw, ih, vw, vh, null)
                none shouldBe frameLayout(iw, ih, vw, vh, ImageFrame.Centered)
                none.scale shouldBe max(vw / iw, vh / ih)
                none.offsetX shouldBe ((vw - none.width) / 2 plusOrMinus tol(none.width))
                none.offsetY shouldBe ((vh - none.height) / 2 plusOrMinus tol(none.height))
            }
        }

        test("P4: more zoom never draws the image smaller") {
            checkAll(imageSide, imageSide, viewportSide, viewportSide, finite(1.0, 4.0), finite(1.0, 4.0)) { iw, ih, vw, vh, a, b ->
                val (low, high) = if (a <= b) a to b else b to a
                frameLayout(iw, ih, vw, vh, ImageFrame(0.5, 0.5, high)).width shouldBeGreaterThanOrEqual
                    frameLayout(iw, ih, vw, vh, ImageFrame(0.5, 0.5, low)).width
            }
        }

        test("P7: one frame looks the same at every size — the layout scales with the viewport") {
            checkAll(imageSide, imageSide, viewportSide, viewportSide, anyFrame, finite(0.1, 10.0)) { iw, ih, vw, vh, f, k ->
                val a = frameLayout(iw, ih, vw, vh, f)
                val b = frameLayout(iw, ih, k * vw, k * vh, f)
                b.offsetX shouldBe (k * a.offsetX plusOrMinus tol(b.width))
                b.offsetY shouldBe (k * a.offsetY plusOrMinus tol(b.height))
                b.width shouldBe (k * a.width plusOrMinus tol(b.width))
                b.height shouldBe (k * a.height plusOrMinus tol(b.height))
            }
        }

        test("P8: canonical is idempotent and lays out exactly like the frame it came from") {
            checkAll(imageSide, imageSide, anyFrame, finite(1.0, 4_000.0)) { iw, ih, f, v ->
                val c = f.canonical(iw, ih)
                val again = c.canonical(iw, ih)
                again.focusX shouldBe (c.focusX plusOrMinus 1e-9)
                again.focusY shouldBe (c.focusY plusOrMinus 1e-9)
                again.zoom shouldBe c.zoom
                val a = frameLayout(iw, ih, v, v, f)
                val b = frameLayout(iw, ih, v, v, c)
                b.offsetX shouldBe (a.offsetX plusOrMinus tol(a.width))
                b.offsetY shouldBe (a.offsetY plusOrMinus tol(a.height))
                b.scale shouldBe a.scale
            }
        }

        test("a portrait with its focus high is pinned to the top edge") {
            val l = frameLayout(300.0, 400.0, 100.0, 100.0, ImageFrame(0.5, 0.2, 1.0))
            l.scale shouldBe (1.0 / 3 plusOrMinus 1e-12)
            l.offsetX shouldBe (0.0 plusOrMinus 1e-9)
            l.offsetY shouldBe 0.0
            l.height shouldBe (400.0 / 3 plusOrMinus 1e-9)
        }

        test("a 16:9 landscape at 2x focused near its left edge lands as computed by hand") {
            // cover = max(100/1600, 100/900) = 1/9, scale = 2/9, drawn 3200/9 × 200.
            // x: 50 − 0.1·355.6 = 14.4 → clamped to the left edge, 0. y: 50 − 0.5·200 = −50, inside [−100, 0].
            val l = frameLayout(1600.0, 900.0, 100.0, 100.0, ImageFrame(0.1, 0.5, 2.0))
            l.scale shouldBe (2.0 / 9 plusOrMinus 1e-12)
            l.width shouldBe (3200.0 / 9 plusOrMinus 1e-9)
            l.height shouldBe (200.0 plusOrMinus 1e-9)
            l.offsetX shouldBe 0.0
            l.offsetY shouldBe (-50.0 plusOrMinus 1e-9)
        }

        test("a size that is not positive and finite draws the viewport unscaled") {
            frameLayout(0.0, 100.0, 48.0, 48.0, ImageFrame.Centered) shouldBe FrameLayout(1.0, 0.0, 0.0, 48.0, 48.0)
            frameLayout(100.0, Double.POSITIVE_INFINITY, 48.0, 48.0, null) shouldBe FrameLayout(1.0, 0.0, 0.0, 48.0, 48.0)
        }

        test("canonical pulls a frame dragged past an edge back to what is shown") {
            // A 3:4 portrait, focus at the very top: the top edge is flush, so the centre shows 0.375 down, not 0.
            val c = ImageFrame(0.5, 0.0, 1.0).canonical(300.0, 400.0)
            c.focusX shouldBe (0.5 plusOrMinus 1e-12)
            c.focusY shouldBe (0.375 plusOrMinus 1e-12)
            ImageFrame.Centered.canonical(300.0, 400.0) shouldBe ImageFrame.Centered
        }
    })
