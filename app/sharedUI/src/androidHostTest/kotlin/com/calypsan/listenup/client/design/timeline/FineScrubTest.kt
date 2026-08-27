package com.calypsan.listenup.client.design.timeline

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [fineScrubStepFor] — the vertical-distance gesture that makes a 65-hour book editable.
 *
 * Dragging a marker horizontally moves it at the lane's own scale; pulling *away* vertically
 * divides that scale down. It is the one precision tool that works at any zoom and on any book
 * length, because it multiplies whatever the lane already gives you rather than depending on it.
 *
 * Tested as a pure function of distance because that is what it is. Wiring it to a pointer is
 * a handful of lines; choosing where the steps fall is the actual design decision, and this is
 * where it is legible and can be changed on purpose.
 */
class FineScrubTest :
    FunSpec({

        test("no vertical pull is full speed — the gesture costs nothing until you use it") {
            fineScrubStepFor(verticalDistanceDp = 0f) shouldBe FineScrubStep.Full
            withClue("a little wobble while dragging horizontally must not silently slow the drag") {
                fineScrubStepFor(verticalDistanceDp = 20f) shouldBe FineScrubStep.Full
            }
        }

        test("pulling further walks down the steps the spec names") {
            fineScrubStepFor(verticalDistanceDp = 70f) shouldBe FineScrubStep.Half
            fineScrubStepFor(verticalDistanceDp = 150f) shouldBe FineScrubStep.Fifth
            fineScrubStepFor(verticalDistanceDp = 400f) shouldBe FineScrubStep.Fine
        }

        test("direction does not matter — pulling up is the same gesture as pulling down") {
            // Which way is "away" depends on where the marker sits on screen; a marker near the top
            // can only be pulled downward. Taking the magnitude is what makes the gesture reachable
            // everywhere in the lane rather than only below its middle.
            fineScrubStepFor(verticalDistanceDp = -150f) shouldBe FineScrubStep.Fifth
            fineScrubStepFor(verticalDistanceDp = -400f) shouldBe FineScrubStep.Fine
        }

        test("each step is the ratio it claims to be") {
            FineScrubStep.Full.ratio shouldBe 1f
            FineScrubStep.Half.ratio shouldBe 0.5f
            FineScrubStep.Fifth.ratio shouldBe 0.2f
            withClue("the spec asks for roughly 25x finer at the deepest step") {
                FineScrubStep.Fine.ratio shouldBe 0.04f
            }
        }

        test("the steps only ever get finer as you pull further") {
            // A curve that folded back on itself would make the gesture feel broken in a way that
            // is very hard to diagnose from a bug report.
            val ratios = listOf(0f, 60f, 100f, 200f, 300f, 800f).map { fineScrubStepFor(it).ratio }
            ratios shouldBe ratios.sortedDescending()
        }

        test("the HUD label matches the step, because the user is being told what they got") {
            FineScrubStep.Full.label shouldBe "1×"
            FineScrubStep.Half shouldBe fineScrubStepFor(70f)
            FineScrubStep.Fifth.label shouldBe "⅕×"
            FineScrubStep.Fine.label shouldBe "25×"
        }

        test("holding shift is the desktop shortcut straight to fine") {
            // The spec gives desktop a keyboard route to the same place: Shift-drag = fine, so a
            // mouse user is not asked to haul the cursor 400dp down the screen.
            fineScrubStepFor(verticalDistanceDp = 0f, shiftHeld = true) shouldBe FineScrubStep.Fine
            withClue("shift wins outright — it is an explicit request, not a hint") {
                fineScrubStepFor(verticalDistanceDp = 500f, shiftHeld = true) shouldBe FineScrubStep.Fine
            }
        }

        test("a step converts a drag in pixels to a span of time") {
            // 1.8s per pixel is a 30-minute window on a ~1000px lane — the editor's working scale.
            val msPerPixel = 1_800.0

            fineScrubStepFor(0f).timeDeltaMs(dragPx = 10f, msPerPixel = msPerPixel) shouldBe 18_000L
            fineScrubStepFor(150f).timeDeltaMs(dragPx = 10f, msPerPixel = msPerPixel) shouldBe 3_600L
            withClue("at the finest step a 10px drag is a few hundred ms — editable by ear") {
                fineScrubStepFor(400f).timeDeltaMs(dragPx = 10f, msPerPixel = msPerPixel) shouldBe 720L
            }
        }
    })
