package com.calypsan.listenup.client.design.util

import androidx.compose.ui.graphics.Color
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.shouldBeWithinPercentageOf
import io.kotest.matchers.shouldBe

/**
 * Tests for [parseHexColor].
 *
 * Covers 6-char and 8-char hex strings (with and without the leading '#'),
 * fallback behaviour on malformed input, and edge-case lengths.
 *
 * Assertions use the floating-point [Color.red]/[Color.green]/[Color.blue]/[Color.alpha]
 * components (each in [0,1]) to avoid platform-specific ARGB packing details.
 */
class ColorUtilTest :
    FunSpec({
        // -----------------------------------------------------------------------
        // 6-char hex (#RRGGBB) — alpha must be fully opaque
        // -----------------------------------------------------------------------

        test("6-char hex with hash produces correct RGB and full alpha") {
            val color = parseHexColor("#FF0000")
            color.red.shouldBeWithinPercentageOf(1f, 0.5)
            color.green shouldBe 0f
            color.blue shouldBe 0f
            color.alpha.shouldBeWithinPercentageOf(1f, 0.5)
        }

        test("6-char hex without hash produces correct RGB and full alpha") {
            val color = parseHexColor("00FF00")
            color.red shouldBe 0f
            color.green.shouldBeWithinPercentageOf(1f, 0.5)
            color.blue shouldBe 0f
            color.alpha.shouldBeWithinPercentageOf(1f, 0.5)
        }

        test("6-char pure blue hex parses correctly") {
            val color = parseHexColor("#0000FF")
            color.red shouldBe 0f
            color.green shouldBe 0f
            color.blue.shouldBeWithinPercentageOf(1f, 0.5)
            color.alpha.shouldBeWithinPercentageOf(1f, 0.5)
        }

        test("6-char white (#FFFFFF) parses correctly") {
            val color = parseHexColor("#FFFFFF")
            color.red.shouldBeWithinPercentageOf(1f, 0.5)
            color.green.shouldBeWithinPercentageOf(1f, 0.5)
            color.blue.shouldBeWithinPercentageOf(1f, 0.5)
            color.alpha.shouldBeWithinPercentageOf(1f, 0.5)
        }

        test("6-char black (#000000) parses correctly") {
            val color = parseHexColor("#000000")
            color.alpha.shouldBeWithinPercentageOf(1f, 0.5)
            // red/green/blue near 0; percentage-based assertions don't work at 0.
            // Assert via the packed int instead.
            val packed =
                (color.alpha * 255 + 0.5f).toInt() shl 24 or
                    ((color.red * 255 + 0.5f).toInt() shl 16) or
                    ((color.green * 255 + 0.5f).toInt() shl 8) or
                    (color.blue * 255 + 0.5f).toInt()
            (packed shr 24 and 0xFF) shouldBe 255 // alpha
            (packed shr 16 and 0xFF) shouldBe 0 // red
            (packed shr 8 and 0xFF) shouldBe 0 // green
            (packed and 0xFF) shouldBe 0 // blue
        }

        test("6-char mixed color (#6B7280) parses with correct channels") {
            val color = parseHexColor("#6B7280")
            // 0x6B = 107, 0x72 = 114, 0x80 = 128
            val r = (color.red * 255 + 0.5f).toInt()
            val g = (color.green * 255 + 0.5f).toInt()
            val b = (color.blue * 255 + 0.5f).toInt()
            r shouldBe 107
            g shouldBe 114
            b shouldBe 128
        }

        // -----------------------------------------------------------------------
        // 8-char hex (#AARRGGBB) — custom alpha
        // -----------------------------------------------------------------------

        test("8-char hex with hash preserves alpha") {
            // #80FF0000 = alpha=0x80=128, red=255, green=0, blue=0
            val color = parseHexColor("#80FF0000")
            val a = (color.alpha * 255 + 0.5f).toInt()
            val r = (color.red * 255 + 0.5f).toInt()
            val g = (color.green * 255 + 0.5f).toInt()
            val b = (color.blue * 255 + 0.5f).toInt()
            a shouldBe 128
            r shouldBe 255
            g shouldBe 0
            b shouldBe 0
        }

        test("8-char hex without hash preserves alpha") {
            // FF6B7280 = fully opaque gray
            val color = parseHexColor("FF6B7280")
            val a = (color.alpha * 255 + 0.5f).toInt()
            a shouldBe 255
        }

        test("8-char fully transparent hex has zero alpha") {
            val color = parseHexColor("#00000000")
            val a = (color.alpha * 255 + 0.5f).toInt()
            a shouldBe 0
        }

        // -----------------------------------------------------------------------
        // Malformed input — must fall back to gray (#FF6B7280), never throw
        // -----------------------------------------------------------------------

        test("empty string falls back to gray") {
            val color = parseHexColor("")
            val r = (color.red * 255 + 0.5f).toInt()
            val g = (color.green * 255 + 0.5f).toInt()
            val b = (color.blue * 255 + 0.5f).toInt()
            r shouldBe 0x6B
            g shouldBe 0x72
            b shouldBe 0x80
        }

        test("string that is only a hash falls back to gray") {
            val color = parseHexColor("#")
            val r = (color.red * 255 + 0.5f).toInt()
            r shouldBe 0x6B // gray fallback red channel
        }

        test("3-char hex (wrong length) falls back to gray") {
            val color = parseHexColor("#FFF")
            val r = (color.red * 255 + 0.5f).toInt()
            r shouldBe 0x6B
        }

        test("7-char hex (wrong length) falls back to gray") {
            val color = parseHexColor("#FFFFFFF")
            val r = (color.red * 255 + 0.5f).toInt()
            r shouldBe 0x6B
        }

        test("non-hex characters fall back to gray") {
            val color = parseHexColor("#GGGGGG")
            val r = (color.red * 255 + 0.5f).toInt()
            r shouldBe 0x6B
        }

        test("random garbage string falls back to gray") {
            val color = parseHexColor("not-a-color")
            val r = (color.red * 255 + 0.5f).toInt()
            r shouldBe 0x6B
        }

        // -----------------------------------------------------------------------
        // Lowercase hex digits are valid
        // -----------------------------------------------------------------------

        test("6-char lowercase hex is parsed correctly") {
            val color = parseHexColor("#ff0000")
            color.red.shouldBeWithinPercentageOf(1f, 0.5)
        }

        test("8-char lowercase hex is parsed correctly") {
            val color = parseHexColor("#80ff0000")
            val a = (color.alpha * 255 + 0.5f).toInt()
            a shouldBe 128
        }

        // -----------------------------------------------------------------------
        // Return type consistency — same input always produces the same result
        // -----------------------------------------------------------------------

        test("same hex string produces equal Color values") {
            val a = parseHexColor("#6B7280")
            val b = parseHexColor("#6B7280")
            a shouldBe b
        }

        // -----------------------------------------------------------------------
        // Direct Color comparison for the default fallback
        // -----------------------------------------------------------------------

        test("fallback gray matches the expected Color constant") {
            val expected = Color(0xFF6B7280.toInt())
            parseHexColor("") shouldBe expected
            parseHexColor("bad") shouldBe expected
        }
    })
