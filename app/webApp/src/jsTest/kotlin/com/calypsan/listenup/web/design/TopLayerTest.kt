package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.ViewportFrame
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.jetbrains.compose.web.dom.Div

private const val WIDTH = 1440

private const val HEIGHT = 900

/**
 * [placeAgainst], the one placement every menu and popover uses: measured against the frame's own viewport, it flips
 * above a trigger near the bottom, hangs from the right edge near the right, and, with room on neither side, takes the
 * roomier one and scrolls there.
 */
class TopLayerTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        suspend fun box(
            width: Int,
            height: Int,
        ): ViewportFrame {
            val frame =
                frames.mount(WIDTH, HEIGHT) {
                    Div(attrs = {
                        classes("probe-box")
                        style {
                            property("position", "fixed")
                            property("width", "${width}px")
                            property("height", "${height}px")
                        }
                    })
                }
            awaitFrame()
            return frame
        }

        test("a box anchored at the bottom-right corner opens above its trigger, inside the right gutter") {
            val frame = box(320, 400)
            val panel = frame.find(".probe-box")

            placeAgainst(panel, MenuAnchor(left = 1380.0, top = 840.0, right = 1430.0, bottom = 884.0))

            val rect = frame.rect(panel)
            rect.bottom shouldBeLessThanOrEqual 840.0
            rect.right shouldBeLessThanOrEqual (WIDTH - 8).toDouble()
            rect.left shouldBeGreaterThanOrEqual 8.0
        }

        test("a box anchored at the top-left opens below, from the trigger's left edge") {
            val frame = box(320, 400)
            val panel = frame.find(".probe-box")

            placeAgainst(panel, MenuAnchor(left = 20.0, top = 10.0, right = 64.0, bottom = 54.0))

            val rect = frame.rect(panel)
            rect.top shouldBeGreaterThanOrEqual 54.0
            rect.left shouldBe (20.0 plusOrMinus 0.5)
        }

        test("with room on neither side it takes the roomier one, capped to the room there") {
            val frame = box(320, 700)
            val panel = frame.find(".probe-box")

            // Below: 900 - 8 - (340 + 6) = 546. Above: 300 - 6 - 8 = 286.
            placeAgainst(panel, MenuAnchor(left = 600.0, top = 300.0, right = 644.0, bottom = 340.0))

            panel.style.getPropertyValue("max-height") shouldBe "546px"
            frame.rect(panel).top shouldBe (346.0 plusOrMinus 0.5)
        }
    })
