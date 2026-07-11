package com.calypsan.listenup.client.campfire

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * TDD suite for the Campfire backdrop's pure fire-palette and geometry math — the color-lerp,
 * particle-aging, and geometry helpers ported from the design reference's `fire.jsx` (Campfire
 * visual-soul upgrade). These are the only parts of the fire simulation cheap and honest enough to
 * unit-test in isolation; the stateful particle engine itself lives in `:sharedUI`.
 */
class CampfireFireVisualMathTest :
    FunSpec({

        val tolerance = 0.001f

        test("flameLickColor is white-gold at the core (t = 0)") {
            val color = flameLickColor(t = 0f, alpha = 0.42f)

            color.red shouldBe (255f / 255f) plusOrMinus tolerance
            color.green shouldBe (248f / 255f) plusOrMinus tolerance
            color.blue shouldBe (205f / 255f) plusOrMinus tolerance
            color.alpha shouldBe 0.42f plusOrMinus tolerance
        }

        test("flameLickColor shifts through amber mid-life") {
            val color = flameLickColor(t = 0.4f, alpha = 1f)

            // g = 178 - (0.4 - 0.28) * 180 = 156.4
            color.red shouldBe 1f plusOrMinus tolerance
            color.green shouldBe (156.4f / 255f) plusOrMinus tolerance
            color.blue shouldBe (60f / 255f) plusOrMinus tolerance
        }

        test("flameLickColor cools to deep ember red near extinguishing") {
            val color = flameLickColor(t = 0.99f, alpha = 1f)

            // r = 224 - (0.99 - 0.8) * 300 = 167
            color.red shouldBe (167f / 255f) plusOrMinus tolerance
            color.green shouldBe (60f / 255f) plusOrMinus tolerance
            color.blue shouldBe (20f / 255f) plusOrMinus tolerance
        }

        test("flameLickColor clamps t outside 0..1") {
            flameLickColor(t = -5f, alpha = 1f) shouldBe flameLickColor(t = 0f, alpha = 1f)
            flameLickColor(t = 5f, alpha = 1f) shouldBe flameLickColor(t = 1f, alpha = 1f)
        }

        test("emberSparkColor stays red-hot and cools slightly with age") {
            val fresh = emberSparkColor(t = 0f, alpha = 1f)
            val aged = emberSparkColor(t = 1f, alpha = 1f)

            fresh.red shouldBe 1f plusOrMinus tolerance
            fresh.green shouldBe (210f / 255f) plusOrMinus tolerance
            aged.green shouldBe (120f / 255f) plusOrMinus tolerance
        }

        test("flameRadiusScale grows through mid-life then tapers near extinguishing") {
            val start = flameRadiusScale(0f)
            val mid = flameRadiusScale(0.5f)
            val end = flameRadiusScale(1f)

            start shouldBe 0.5f plusOrMinus tolerance
            mid shouldBe 1.2f plusOrMinus tolerance
            (mid > end) shouldBe true
        }

        test("flameAlphaEnvelope fades in quickly then fades out to zero") {
            flameAlphaEnvelope(0f) shouldBe 0f plusOrMinus tolerance
            flameAlphaEnvelope(0.15f) shouldBe 0.42f plusOrMinus tolerance
            flameAlphaEnvelope(1f) shouldBe 0f plusOrMinus tolerance
        }

        test("computeFireGeometry centers the base horizontally and caps its width") {
            val geometry = computeFireGeometry(widthPx = 1000f, heightPx = 2000f, baseYFraction = 0.72f, maxBaseWidthPx = 138f)

            geometry.baseX shouldBe 500f plusOrMinus tolerance
            geometry.baseY shouldBe 1440f plusOrMinus tolerance
            // widthPx * 0.28 = 280, capped to 138
            geometry.baseWidth shouldBe 138f plusOrMinus tolerance
        }

        test("computeFireGeometry uses the proportional width when under the cap") {
            val geometry = computeFireGeometry(widthPx = 300f, heightPx = 600f, baseYFraction = 0.5f, maxBaseWidthPx = 138f)

            // widthPx * 0.28 = 84, under the 138 cap
            geometry.baseWidth shouldBe 84f plusOrMinus tolerance
        }

        test("glowValue clamps into its breathing range") {
            glowValue(flick = 0.7f, jitter = 0f, intensity = 1f) shouldBe 0.77f plusOrMinus tolerance
            glowValue(flick = 10f, jitter = 0f, intensity = 1f) shouldBe 1.15f plusOrMinus tolerance
            glowValue(flick = -10f, jitter = 0f, intensity = 1f) shouldBe 0.35f plusOrMinus tolerance
        }

        test("glowLayerCenteringOffsetPx re-centers a bottom-anchored layer on baseY") {
            // canvas 2000px tall, baseY at 1440 (0.72 fraction); a 340px-tall layer anchored to the
            // canvas bottom naturally centers at 1830 (2000 - 340/2) — 390px below baseY — so the
            // offset must shift it up by 390px to land on the fire's origin.
            val offset = glowLayerCenteringOffsetPx(baseY = 1440f, canvasHeightPx = 2000f, layerHeightPx = 340f)

            offset shouldBe (-390f) plusOrMinus tolerance
        }

        test("glowLayerCenteringOffsetPx is zero when baseY already matches the layer's natural center") {
            val offset = glowLayerCenteringOffsetPx(baseY = 1830f, canvasHeightPx = 2000f, layerHeightPx = 340f)

            offset shouldBe 0f plusOrMinus tolerance
        }
    })
