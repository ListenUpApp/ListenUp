package com.calypsan.listenup.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
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
    fun `does not flag label on AnimatedContent (animation debug identifier)`() {
        assertEquals(0, rule.lint("""@Composable fun S() { AnimatedContent(s, label = "upload_state") {} }""").size)
    }

    @Test
    fun `does not flag label on animateXAsState`() {
        assertEquals(0, rule.lint("""@Composable fun S() { val a = animateFloatAsState(1f, label = "fade") }""").size)
    }

    @Test
    fun `still flags label on a real UI component`() {
        assertEquals(1, rule.lint("""@Composable fun S() { StatItem(label = "Books") }""").size)
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

    @Test
    fun `flags custom component text named arg`() {
        assertEquals(1, rule.lint("""@Composable fun S() { ListenUpButton(text = "Save") }""").size)
    }

    @Test
    fun `flags dialog title confirmText and dismissText`() {
        assertEquals(
            3,
            rule
                .lint(
                    """@Composable fun S() { Dialog(title = "Settings", confirmText = "OK", dismissText = "Cancel") }""",
                ).size,
        )
    }

    @Test
    fun `flags field label and placeholder`() {
        assertEquals(
            2,
            rule.lint("""@Composable fun S() { MyField(label = "Name", placeholder = "Enter name") }""").size,
        )
    }

    @Test
    fun `flags card subtitle and headline`() {
        assertEquals(
            2,
            rule.lint("""@Composable fun S() { Card(subtitle = "Sub", headline = "Head") }""").size,
        )
    }

    @Test
    fun `flags named text exactly once`() {
        assertEquals(1, rule.lint("""@Composable fun S() { Text(text = "x") }""").size)
    }

    @Test
    fun `does not flag punctuation-only custom component text`() {
        assertEquals(0, rule.lint("""@Composable fun S() { Button(text = "•") }""").size)
    }
}
