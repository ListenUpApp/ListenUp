package com.calypsan.listenup.client.design.text

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Text reads in its own direction, from its first strong letter, else left to right when it has none, and UI labels sit
 * on the layout's start side (left or right by side, not by the paragraph). Prose aligns to its own paragraphs instead.
 * Compose's default lays every paragraph out in the layout's direction, so English strings in an Arabic or Hebrew locale
 * move their commas, minus signs and hyphens to the wrong end.
 */
class ContentTextDirectionTest :
    FunSpec({
        test("an English string flows left to right") {
            firstStrongDirection("Narrated by ") shouldBe LayoutDirection.Ltr
        }

        test("an Arabic or Hebrew string flows right to left") {
            firstStrongDirection("رواه") shouldBe LayoutDirection.Rtl
            firstStrongDirection("קריינות") shouldBe LayoutDirection.Rtl
        }

        test("the first letter decides, past digits and punctuation") {
            firstStrongDirection("1-day streak") shouldBe LayoutDirection.Ltr
            firstStrongDirection("12 رواه") shouldBe LayoutDirection.Rtl
        }

        test("digits and punctuation alone have no direction") {
            firstStrongDirection("-1:52") shouldBe null
            firstStrongDirection("") shouldBe null
        }

        test("scripts beyond the Basic Multilingual Plane are read by code point") {
            // Adlam (U+1E900) and Hanifi Rohingya (U+10D00) are right-to-left, written as surrogate pairs.
            firstStrongDirection("\uD83A\uDD00") shouldBe LayoutDirection.Rtl
            firstStrongDirection("1 \uD803\uDD00") shouldBe LayoutDirection.Rtl
            // An emoji is not a letter: the letter after it decides.
            firstStrongDirection("\uD83D\uDE00 Hello") shouldBe LayoutDirection.Ltr
        }

        test("the directional marks decide where they come first") {
            firstStrongDirection("\u200E-1:52") shouldBe LayoutDirection.Ltr
            firstStrongDirection("\u200FHello") shouldBe LayoutDirection.Rtl
            firstStrongDirection("\u061C12") shouldBe LayoutDirection.Rtl
        }

        test("prose aligns to its own paragraph's start, whatever the layout") {
            Typography().readingFromContent(LayoutDirection.Rtl).bodyLarge.forProse().textAlign shouldBe TextAlign.Start
        }

        test("every text style the typography has is one the theme re-reads, found by reflection") {
            // A new M3 type role must not slip past readingFromContent: the getters are the ground truth.
            Typography().reflectedStyles() shouldHaveSize Typography().allStyles().size
        }

        test("in a right-to-left layout every style reads from its content, else left to right, and aligns right") {
            Typography().readingFromContent(LayoutDirection.Rtl).reflectedStyles().forEach { style ->
                style.textDirection shouldBe TextDirection.ContentOrLtr
                style.textAlign shouldBe TextAlign.Right
            }
        }

        test("in a left-to-right layout every style reads from its content, else left to right, and aligns left") {
            Typography().readingFromContent(LayoutDirection.Ltr).reflectedStyles().forEach { style ->
                style.textDirection shouldBe TextDirection.ContentOrLtr
                style.textAlign shouldBe TextAlign.Left
            }
        }
    })


/** Every text style this typography exposes, read through its getters, so no role is missed by a hand-kept list. */
private fun Typography.reflectedStyles(): List<TextStyle> =
    Typography::class.java.methods
        .filter { it.parameterCount == 0 && it.returnType == TextStyle::class.java }
        .map { it.invoke(this) as TextStyle }
