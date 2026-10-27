package com.calypsan.listenup.client.presentation.player

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class PlayerMorphTest :
    FunSpec({
        context("where a release settles: velocity first, then the halfway line") {
            test("a slow release past halfway opens") {
                settleTarget(progress = 0.51f, velocityDpPerSec = -100f) shouldBe MorphEnd.NowPlaying
            }
            test("a slow release short of halfway springs back to the bar") {
                settleTarget(0.49f, 100f) shouldBe MorphEnd.Bar
            }
            test("exactly halfway, standing still, stays closed") {
                settleTarget(0.5f, 0f) shouldBe MorphEnd.Bar
            }
            test("a fast flick up opens from anywhere") {
                settleTarget(0.05f, -721f) shouldBe MorphEnd.NowPlaying
            }
            test("a fast flick down closes from 80 %") {
                settleTarget(0.8f, 721f) shouldBe MorphEnd.Bar
            }
            test("the fling threshold is the trigger's, 720 dp/s, and is exclusive") {
                settleTarget(0.2f, -PlayerMorph.FLING_DP_PER_SEC) shouldBe MorphEnd.Bar
            }
            test("up to 70 % and back to 30 % before letting go springs back") {
                settleTarget(0.3f, 300f) shouldBe MorphEnd.Bar
            }
        }

        context("the halfway tick") {
            test("fires crossing up and crossing down") {
                crossesHalfway(0.48f, 0.52f) shouldBe true
                crossesHalfway(0.52f, 0.48f) shouldBe true
            }
            test("never while staying on one side") {
                crossesHalfway(0.1f, 0.49f) shouldBe false
            }
        }

        context("the arc") {
            test("x eases in, y eases out, both reach their ends") {
                arc(0f) shouldBe Arc(0f, 0f)
                arc(1f) shouldBe Arc(1f, 1f)
                arc(0.5f).x shouldBe (0.3536f plusOrMinus 1e-4f)
                arc(0.5f).y shouldBe (0.6464f plusOrMinus 1e-4f)
            }
            test("past 1 (spring overshoot) it extrapolates rather than clamps, so the overshoot shows on the cover") {
                arc(1.02f).y shouldBeGreaterThan 1f
            }
        }

        context("a flight between two measured rects") {
            val bar = MorphRect(left = 12f, top = 792f, width = 48f, height = 48f)
            val page = MorphRect(left = 56f, top = 108f, width = 300f, height = 300f)
            test("starts on the bar and lands on the page") {
                flight(bar, page, 0f) shouldBe bar
                flight(bar, page, 1f) shouldBe page
            }
            test("halfway, it has swung up further than across (the canvas's MorphAndroid at 0.5)") {
                val mid = flight(bar, page, 0.5f)
                mid.left shouldBe (12f + 44f * 0.35355339f plusOrMinus 0.01f)
                mid.top shouldBe (792f - 684f * 0.64644661f plusOrMinus 0.01f)
                mid.width shouldBe 174f
            }
        }

        context("the back preview, carried into the close") {
            test("at full progress it is the preview the gesture left; at the bar it is gone") {
                val preview = BackPreviewWeight(scale = 0.9f, translationX = 12f, cornerFraction = 1f)
                preview.at(1f) shouldBe preview
                preview.at(0f) shouldBe BackPreviewWeight.None
                preview.at(0.5f).scale shouldBe (0.95f plusOrMinus 1e-6f)
            }
        }

        context("reduce motion") {
            test("removed animations crossfade") { morphMode(reduceMotion = true) shouldBe MorphMode.Crossfade }
            test("otherwise the morph") { morphMode(reduceMotion = false) shouldBe MorphMode.Morph }
        }

        test("progress from a drag: the sheet's top edge over the full travel, clamped") {
            progressOf(sheetTopPx = 387.5f, barTopPx = 775f) shouldBe 0.5f
            progressOf(sheetTopPx = -10f, barTopPx = 775f) shouldBe 1f
            progressOf(sheetTopPx = 800f, barTopPx = 775f) shouldBe 0f
        }

        test("a velocity in dp/s becomes one in progress/s for the spring") {
            progressVelocity(velocityDpPerSec = -775f, travelDp = 775f) shouldBe 1f
        }
    })
