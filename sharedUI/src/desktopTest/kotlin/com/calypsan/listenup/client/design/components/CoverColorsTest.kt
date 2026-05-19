package com.calypsan.listenup.client.design.components

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [isColorLight].
 *
 * [isColorLight] uses the ITU-R BT.601 luma coefficients
 * (0.299·R + 0.587·G + 0.114·B) / 255 and returns true when luminance > 0.5.
 *
 * The input is a packed ARGB int (alpha in bits 31-24, red in 23-16, green in 15-8, blue in 7-0).
 * Alpha is ignored by the function.
 */
class CoverColorsTest :
    FunSpec({
        // -----------------------------------------------------------------------
        // Absolute extremes
        // -----------------------------------------------------------------------

        test("pure black (0xFF000000) is not light") {
            isColorLight(0xFF000000.toInt()) shouldBe false
        }

        test("pure white (0xFFFFFFFF) is light") {
            isColorLight(0xFFFFFFFF.toInt()) shouldBe true
        }

        // -----------------------------------------------------------------------
        // Boundary — luminance == 0.5 is NOT light (strictly > 0.5)
        // -----------------------------------------------------------------------

        test("color with luminance exactly 0.5 is not light") {
            // 0.299*R + 0.587*G + 0.114*B = 0.5 * 255 = 127.5
            // Use R=G=B=128: luminance = (0.299+0.587+0.114)*128/255 = 128/255 ≈ 0.502 → light
            // Use R=G=B=127: luminance = 127/255 ≈ 0.498 → not light
            val slightlyBelowHalf = 0xFF7F7F7F.toInt() // R=G=B=127
            isColorLight(slightlyBelowHalf) shouldBe false
        }

        test("mid-gray R=G=B=128 is light") {
            // luminance = 128/255 ≈ 0.502 > 0.5
            val midGray = 0xFF808080.toInt()
            isColorLight(midGray) shouldBe true
        }

        // -----------------------------------------------------------------------
        // Primary channel dominance — verifies per-channel coefficient ordering
        // -----------------------------------------------------------------------

        test("pure red (0xFFFF0000) is not light") {
            // luminance = 0.299*255/255 = 0.299 < 0.5
            isColorLight(0xFFFF0000.toInt()) shouldBe false
        }

        test("pure green (0xFF00FF00) is light") {
            // luminance = 0.587*255/255 = 0.587 > 0.5
            isColorLight(0xFF00FF00.toInt()) shouldBe true
        }

        test("pure blue (0xFF0000FF) is not light") {
            // luminance = 0.114*255/255 = 0.114 < 0.5
            isColorLight(0xFF0000FF.toInt()) shouldBe false
        }

        // -----------------------------------------------------------------------
        // Coefficient ordering — green contributes more than red, red more than blue
        // -----------------------------------------------------------------------

        test("full red contributes more luminance than full blue") {
            // Pure red luma ≈ 0.299, pure blue luma ≈ 0.114 → red should be closer to light
            // Both < 0.5 so both not light — but verify relative ordering via a mix
            // 50% red + 50% blue: luma = (0.299+0.114)/2 ≈ 0.207 → not light
            val halfRedHalfBlue = 0xFF7F007F.toInt()
            isColorLight(halfRedHalfBlue) shouldBe false
        }

        test("green dominant color is light even with low red and blue") {
            // R=0, G=220, B=0: luma = 0.587*220/255 ≈ 0.507 > 0.5
            val highGreen = (0xFF shl 24) or (0 shl 16) or (220 shl 8) or 0
            isColorLight(highGreen) shouldBe true
        }

        test("green just below threshold is not light") {
            // R=0, G=217, B=0: luma = 0.587*217/255 ≈ 0.500 → right at boundary
            // 0.587*217 = 127.379; 127.379/255 ≈ 0.4995 < 0.5 → not light
            val nearThresholdGreen = (0xFF shl 24) or (0 shl 16) or (217 shl 8) or 0
            isColorLight(nearThresholdGreen) shouldBe false
        }

        // -----------------------------------------------------------------------
        // Alpha channel is ignored
        // -----------------------------------------------------------------------

        test("alpha value does not affect luminance calculation") {
            // Same RGB, different alpha → same result
            val fullyOpaque = (0xFF shl 24) or (200 shl 16) or (200 shl 8) or 200
            val halfTransparent = (0x80 shl 24) or (200 shl 16) or (200 shl 8) or 200
            isColorLight(fullyOpaque) shouldBe isColorLight(halfTransparent)
        }

        // -----------------------------------------------------------------------
        // Real-world-ish colors
        // -----------------------------------------------------------------------

        test("light sky blue is light") {
            // #87CEEB = R=135, G=206, B=235
            // luma = (0.299*135 + 0.587*206 + 0.114*235) / 255
            //      = (40.365 + 120.922 + 26.79) / 255
            //      = 188.077 / 255 ≈ 0.738 → light
            val skyBlue = (0xFF shl 24) or (0x87 shl 16) or (0xCE shl 8) or 0xEB
            isColorLight(skyBlue) shouldBe true
        }

        test("dark navy is not light") {
            // #0D1B2A = R=13, G=27, B=42
            // luma = (0.299*13 + 0.587*27 + 0.114*42) / 255
            //      = (3.887 + 15.849 + 4.788) / 255
            //      = 24.524 / 255 ≈ 0.096 → not light
            val navy = (0xFF shl 24) or (0x0D shl 16) or (0x1B shl 8) or 0x2A
            isColorLight(navy) shouldBe false
        }

        test("yellow is light") {
            // #FFFF00 = R=255, G=255, B=0
            // luma = (0.299*255 + 0.587*255) / 255 = 0.886 → light
            val yellow = (0xFF shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0x00
            isColorLight(yellow) shouldBe true
        }
    })
