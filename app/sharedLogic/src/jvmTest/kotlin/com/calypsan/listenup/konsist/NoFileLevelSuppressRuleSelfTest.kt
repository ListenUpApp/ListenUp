package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs, so the rule is known to fire before it is trusted to stay quiet. */
class NoFileLevelSuppressRuleSelfTest :
    FunSpec({
        test("a file-level suppression is detected") {
            val source =
                """
                |@file:Suppress("MagicNumber")
                |package a
                """.trimMargin()
            hasFileLevelSuppress(source) shouldBe true
        }

        test("a declaration-level suppression and a mention in prose are not") {
            val source =
                """
                |package a
                |// no @file:Suppress here
                |@Suppress("MagicNumber")
                |fun f() = 1
                """.trimMargin()
            hasFileLevelSuppress(source) shouldBe false
        }
    })
