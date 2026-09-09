package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs for [callsMissingKeyboardOptions], so the rule is known to fire before it is trusted. */
class TextFieldsDeclareKeyboardOptionsRuleSelfTest :
    FunSpec({
        test("a call without keyboardOptions is reported by line") {
            val source =
                """
                |@Composable
                |fun Screen() {
                |    ListenUpTextField(
                |        value = name,
                |        onValueChange = onName,
                |    )
                |}
                """.trimMargin()
            callsMissingKeyboardOptions(source) shouldBe listOf(3)
        }

        test("a call that declares keyboardOptions is not reported") {
            val source =
                """
                |ListenUpTextField(
                |    value = name,
                |    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                |)
                """.trimMargin()
            callsMissingKeyboardOptions(source) shouldBe emptyList()
        }

        test("the definition itself is not a call") {
            val definition =
                """
                |fun ListenUpTextField(
                |    value: String,
                |) {
                |}
                """.trimMargin()
            callsMissingKeyboardOptions(definition) shouldBe emptyList()
        }

        test("a parenthesis inside a string argument does not end the call early") {
            // Without string awareness the ')' in the label closes the scan before keyboardOptions
            // is seen, and a compliant call is reported. With it, the second call is the offender.
            val source =
                """
                |ListenUpTextField(
                |    label = "Name (optional)",
                |    keyboardOptions = KeyboardOptions.Default,
                |)
                |ListenUpTextField(
                |    label = "Plain",
                |)
                """.trimMargin()
            callsMissingKeyboardOptions(source) shouldBe listOf(5)
        }
    })
