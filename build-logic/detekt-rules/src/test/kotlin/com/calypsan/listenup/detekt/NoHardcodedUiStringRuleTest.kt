package com.calypsan.listenup.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

class NoHardcodedUiStringRuleTest {
    private val rule = NoHardcodedUiStringRule(Config.empty)

    @Test
    fun `flags Text with positional string literal`() {
        assertEquals(1, rule.lint("""@Composable fun S() { Text("Go to Book") }""").size)
    }

    @Test
    fun `flags Text with named text string literal`() {
        assertEquals(1, rule.lint("""@Composable fun S() { Text(text = "Close Book") }""").size)
    }

    @Test
    fun `flags contentDescription string literal in any call`() {
        assertEquals(1, rule.lint("""@Composable fun S() { Icon(x, contentDescription = "Cast") }""").size)
    }

    @Test
    fun `flags interpolated literal`() {
        assertEquals(1, rule.lint("""@Composable fun S(n: Int) { Text("Show all ${'$'}n chapters") }""").size)
    }

    @Test
    fun `does not flag Text with stringResource`() {
        assertEquals(0, rule.lint("""@Composable fun S() { Text(stringResource(Res.string.x)) }""").size)
    }

    @Test
    fun `does not flag contentDescription with stringResource`() {
        assertEquals(0, rule.lint("""@Composable fun S() { Icon(x, contentDescription = stringResource(Res.string.x)) }""").size)
    }

    @Test
    fun `does not flag testTag string literal`() {
        assertEquals(0, rule.lint("""@Composable fun S() { M.testTag("home_grid") }""").size)
    }

    @Test
    fun `does not flag punctuation-only Text`() {
        assertEquals(0, rule.lint("""@Composable fun S() { Text("•") }""").size)
    }

    @Test
    fun `does not flag literal inside a Preview function`() {
        assertEquals(0, rule.lint("""@Preview @Composable fun P() { Text("Speed") }""").size)
    }

    @Test
    fun `does not flag contentDescription null`() {
        assertEquals(0, rule.lint("""@Composable fun S() { Icon(x, contentDescription = null) }""").size)
    }
}
