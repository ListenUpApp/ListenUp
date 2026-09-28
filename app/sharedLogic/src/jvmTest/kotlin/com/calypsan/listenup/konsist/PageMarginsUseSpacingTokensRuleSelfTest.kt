package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs for [rawPageMarginLines], so the rule is known to fire before it is trusted. */
class PageMarginsUseSpacingTokensRuleSelfTest :
    FunSpec({
        test("each page-margin shape with a raw 16 or 24dp is reported by line") {
            val source =
                """
                |Modifier.padding(horizontal = 24.dp)
                |Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                |Modifier.padding(24.dp)
                |PaddingValues(16.dp)
                |PaddingValues(
                |    start = 24.dp,
                |    end = 24.dp,
                |)
                """.trimMargin()
            rawPageMarginLines(source) shouldBe listOf(1, 2, 3, 4, 5)
        }

        test("tokens, other values and asymmetric insets are not reported") {
            val source =
                """
                |Modifier.padding(horizontal = Spacing.screenMargin)
                |Modifier.padding(Spacing.lg)
                |Modifier.padding(horizontal = 20.dp)
                |Modifier.padding(vertical = 24.dp)
                |Modifier.padding(start = 16.dp, top = 12.dp)
                |Modifier.padding(16.dp).size(24.dp)
                """.trimMargin()
            // Line 6's padding(16.dp) is all-sides, so it IS a finding; its size(24.dp) is geometry.
            rawPageMarginLines(source) shouldBe listOf(6)
        }

        test("a margin quoted in a comment is not reported") {
            val source =
                """
                |// the wide hero is an already-inset panel (padding(24.dp) inside)
                |/* padding(horizontal = 16.dp) */
                |Modifier.padding(Spacing.xl)
                """.trimMargin()
            rawPageMarginLines(source) shouldBe emptyList()
        }

        test("a quote character literal does not hide the code after it") {
            val source =
                """
                |val quote = '"'
                |Modifier.padding(24.dp)
                """.trimMargin()
            rawPageMarginLines(source) shouldBe listOf(2)
        }

        test("a call nested inside another keeps its own line") {
            val source =
                """
                |Column(
                |    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 24.dp),
                |)
                """.trimMargin()
            rawPageMarginLines(source) shouldBe listOf(2)
        }
    })
