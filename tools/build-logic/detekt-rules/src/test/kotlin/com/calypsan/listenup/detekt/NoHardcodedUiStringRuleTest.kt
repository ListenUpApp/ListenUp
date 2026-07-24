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

    // Regression: `flagIfHardcoded` cast the argument to KtStringTemplateExpression and bailed on
    // anything else, so a literal nested in ANY non-literal expression was silently skipped. The
    // player shipped `Text(if (state.hasMultipleAuthors) "Go to Author…" else "Go to Author")` —
    // hardcoded and un-localizable — with this rule green.
    @Test
    fun `flags literals in an if-else branch`() {
        assertEquals(
            2,
            rule.lint("""@Composable fun S(m: Boolean) { Text(if (m) "Go to Author…" else "Go to Author") }""").size,
        )
    }

    @Test
    fun `flags literals in a when branch`() {
        assertEquals(
            2,
            rule
                .lint(
                    """@Composable fun S(n: Int) { Text(when (n) { 0 -> "None yet" else -> "Some" }) }""",
                ).size,
        )
    }

    @Test
    fun `flags a literal in an elvis fallback`() {
        assertEquals(1, rule.lint("""@Composable fun S(t: String?) { Text(t ?: "Untitled") }""").size)
    }

    @Test
    fun `flags a hardcoded contentDescription inside a conditional`() {
        assertEquals(
            2,
            rule
                .lint(
                    """@Composable fun S(p: Boolean) { Icon(x, contentDescription = if (p) "Pause" else "Play") }""",
                ).size,
        )
    }

    @Test
    fun `does not flag stringResource branches inside a conditional`() {
        assertEquals(
            0,
            rule
                .lint(
                    """@Composable fun S(m: Boolean) { Text(if (m) stringResource(Res.string.a) else stringResource(Res.string.b)) }""",
                ).size,
        )
    }

    @Test
    fun `does not flag an interpolation with only punctuation literal parts`() {
        // `"${'$'}greeting,"` is an interpolated value plus a comma — no authored copy. The template's
        // SOURCE text contains the identifier's letters, so a naive letter check misreads it.
        assertEquals(
            0,
            rule.lint("""@Composable fun S(g: String) { Text(text = "${'$'}g,") }""").size,
        )
    }

    @Test
    fun `does not flag punctuation-only literals inside a conditional`() {
        assertEquals(0, rule.lint("""@Composable fun S(m: Boolean) { Text(if (m) "•" else "—") }""").size)
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
