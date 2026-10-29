package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs, so the rule is known to fire before it is trusted to stay quiet. */
class TextAlignFollowsLayoutRuleSelfTest :
    FunSpec({
        test("aligning by the paragraph's start or end is detected") {
            alignsByParagraph("Text(title, textAlign = TextAlign.Start)") shouldBe true
            alignsByParagraph("val a = if (c) TextAlign.Center else TextAlign.End") shouldBe true
        }

        test("the layout's edges and the absolute sides are not") {
            alignsByParagraph("Text(title, textAlign = TextAlign.LayoutStart)") shouldBe false
            alignsByParagraph("textAlign = TextAlign.Right") shouldBe false
            alignsByParagraph("textAlign = TextAlign.Center") shouldBe false
        }
    })
