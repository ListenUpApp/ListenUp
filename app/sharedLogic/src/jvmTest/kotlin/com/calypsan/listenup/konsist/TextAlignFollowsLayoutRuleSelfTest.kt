package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/** Planted inputs, so the rules are known to fire before they are trusted to stay quiet. */
class TextAlignFollowsLayoutRuleSelfTest :
    FunSpec({
        test("aligning by the paragraph's start, end or justification is detected") {
            alignsByParagraph("Text(title, textAlign = TextAlign.Start)") shouldBe true
            alignsByParagraph("val a = if (c) TextAlign.Center else TextAlign.End") shouldBe true
            alignsByParagraph("textAlign = TextAlign.Justify") shouldBe true
            alignsByParagraph("textAlign = TextAlign.Companion.Start") shouldBe true
        }

        test("the layout's edges and the absolute sides are not") {
            alignsByParagraph("Text(title, textAlign = TextAlign.LayoutStart)") shouldBe false
            alignsByParagraph("textAlign = TextAlign.Right") shouldBe false
            alignsByParagraph("textAlign = TextAlign.Center") shouldBe false
        }

        test("a text style built from nothing, without a direction, is detected, nested parentheses and all") {
            rawTextStylesWithoutDirection("val s = TextStyle(fontSize = 11.sp, color = c.copy(alpha = 0.5f))") shouldHaveSize 1
            rawTextStylesWithoutDirection("val s = TextStyle()") shouldHaveSize 1
        }

        test("a style that sets its direction, one copied from the theme, or the type itself, is not") {
            rawTextStylesWithoutDirection("TextStyle(fontSize = 11.sp, textDirection = TextDirection.Ltr)").shouldBeEmpty()
            rawTextStylesWithoutDirection("MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)").shouldBeEmpty()
            rawTextStylesWithoutDirection("fun f(style: TextStyle): TextStyle = LocalTextStyle.current").shouldBeEmpty()
            rawTextStylesWithoutDirection("import androidx.compose.ui.text.TextStyle").shouldBeEmpty()
        }
    })
