package com.calypsan.listenup.client.design.text

import androidx.compose.material3.Typography
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Text reads in its own direction, and sits where the layout puts it: Android's `textDirection="firstStrong"` with
 * gravity-start alignment. Compose's default instead lays every paragraph out in the layout's direction, so English
 * strings in an Arabic or Hebrew locale move their commas, minus signs and hyphens to the wrong end.
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

        test("in a right-to-left layout every style reads from its content, else left to right, and aligns right") {
            val styles = Typography().readingFromContent(LayoutDirection.Rtl).allStyles()
            styles shouldHaveSize TYPE_ROLES
            styles.forEach { style ->
                style.textDirection shouldBe TextDirection.ContentOrLtr
                style.textAlign shouldBe TextAlign.Right
            }
        }

        test("in a left-to-right layout every style reads from its content, else left to right, and aligns left") {
            Typography().readingFromContent(LayoutDirection.Ltr).allStyles().forEach { style ->
                style.textDirection shouldBe TextDirection.ContentOrLtr
                style.textAlign shouldBe TextAlign.Left
            }
        }
    })

/** The fifteen baseline type roles and their fifteen emphasized twins. */
private const val TYPE_ROLES = 30
