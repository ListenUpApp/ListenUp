package com.calypsan.listenup.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

class NoKoinInjectViewModelRuleTest {
    private val rule = NoKoinInjectViewModelRule(Config.empty)

    @Test
    fun `flags koinInject of generic ViewModel type`() {
        val findings =
            rule.lint(
                """
                class Test {
                    fun foo() {
                        val vm = koinInject<LoginViewModel>()
                    }
                }
                """.trimIndent(),
            )
        assertEquals(1, findings.size)
    }

    @Test
    fun `flags default-param ViewModel via koinInject`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginScreen(
                    viewModel: LoginViewModel = koinInject(),
                ) {}
                """.trimIndent(),
            )
        assertEquals(1, findings.size)
    }

    @Test
    fun `does not flag koinInject of a non-ViewModel type`() {
        val findings =
            rule.lint(
                """
                class Test {
                    fun foo() {
                        val analytics = koinInject<AnalyticsService>()
                    }
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }

    @Test
    fun `does not flag koinViewModel`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginScreen(
                    viewModel: LoginViewModel = koinViewModel(),
                ) {}
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }

    @Test
    fun `does not flag koinNavViewModel`() {
        val findings =
            rule.lint(
                """
                @Composable
                fun LoginScreen(
                    viewModel: LoginViewModel = koinNavViewModel(),
                ) {}
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }

    @Test
    fun `does not flag types ending with ViewModelFactory`() {
        val findings =
            rule.lint(
                """
                class Test {
                    fun foo() {
                        val factory = koinInject<LoginViewModelFactory>()
                    }
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size, "ViewModelFactory should not match the rule's regex")
    }
}
