package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs for [rawTopAppBarLines], so the rule is known to fire before it is trusted. */
class FeatureScreensUseTheOneTopBarRuleSelfTest :
    FunSpec({
        test("a raw top app bar of any size is reported by line, qualified or not") {
            val source =
                """
                |topBar = {
                |    TopAppBar(
                |        title = { Text("Settings") },
                |    )
                |}
                |CenterAlignedTopAppBar(title = {})
                |androidx.compose.material3.LargeTopAppBar(title = {})
                """.trimMargin()
            rawTopAppBarLines(source) shouldBe listOf(2, 6, 7)
        }

        test("the one bar and Material's bar defaults are not reported") {
            val source =
                """
                |ListenUpTopAppBar(title = "Settings", onBack = onBack)
                |colors = TopAppBarDefaults.topAppBarColors(containerColor = surface)
                |val behavior = TopAppBarDefaults.pinnedScrollBehavior()
                """.trimMargin()
            rawTopAppBarLines(source) shouldBe emptyList()
        }

        test("a bar named in a comment is not reported") {
            val source =
                """
                |// was TopAppBar(title = { … })
                |/* not a Material `TopAppBar(`, a header band */
                """.trimMargin()
            rawTopAppBarLines(source) shouldBe emptyList()
        }
    })
