package com.calypsan.listenup.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import dev.detekt.test.utils.compileContentForTest
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
    fun `flags a literal passed as a named text argument to any call`() {
        assertEquals(1, rule.lint("""@Composable fun S() { ListenUpButton(text = "Retry", onClick = {}) }""").size)
    }

    @Test
    fun `flags named title message label and placeholder literals`() {
        assertEquals(
            4,
            rule
                .lint(
                    """@Composable fun S() { D(title = "A", message = "B", label = "C", placeholder = "D") }""",
                ).size,
        )
    }

    @Test
    fun `flags the first argument of showSnackbar`() {
        assertEquals(1, rule.lint("""suspend fun S() { snackbarHostState.showSnackbar("Saved") }""").size)
    }

    @Test
    fun `flags the first argument of shareText`() {
        assertEquals(1, rule.lint("""suspend fun S() { shareActions.shareText("Check out x", url) }""").size)
    }

    @Test
    fun `does not flag a format value passed to stringResource`() {
        assertEquals(0, rule.lint("""@Composable fun S() { stringResource(Res.string.x, "Bob") }""").size)
    }

    @Test
    fun `does not flag a semantics contentDescription assignment from an identifier`() {
        assertEquals(0, rule.lint("""@Composable fun S(name: String) { M.semantics { contentDescription = name } }""").size)
    }

    @Test
    fun `does not flag punctuation-only named text`() {
        assertEquals(0, rule.lint("""@Composable fun S() { B(text = "•") }""").size)
    }

    @Test
    fun `does not flag a named text argument that is an identifier`() {
        assertEquals(0, rule.lint("""@Composable fun S(t: String) { B(text = t) }""").size)
    }

    @Test
    fun `does not flag named text literals inside a Preview function`() {
        assertEquals(0, rule.lint("""@Preview @Composable fun P() { B(text = "Retry") }""").size)
    }

    @Test
    fun `does not flag the label of an animation API`() {
        assertEquals(0, rule.lint("""@Composable fun S() { val a by animateDpAsState(x, label = "deckX") }""").size)
        assertEquals(0, rule.lint("""@Composable fun S() { AnimatedContent(x, label = "ButtonContent") { } }""").size)
    }

    @Test
    fun `flags the label of a non-animation call`() {
        assertEquals(1, rule.lint("""@Composable fun S() { Field(label = "Email") }""").size)
    }

    @Test
    fun `does not flag literals in a preview file`() {
        assertEquals(
            0,
            rule.lint(compileContentForTest("""@Composable fun S() { B(text = "Retry") }""", "HomePreviews.kt")).size,
        )
    }

    @Test
    fun `does not flag contentDescription null`() {
        assertEquals(0, rule.lint("""@Composable fun S() { Icon(x, contentDescription = null) }""").size)
    }
}
