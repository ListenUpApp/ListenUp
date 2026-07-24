package com.calypsan.listenup.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleName
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Forbids hardcoded user-facing string literals in Compose UI: `Text("literal")`,
 * `Text(text = "literal")`, and `contentDescription = "literal"`. Use
 * `stringResource(Res.string.…)` (backed by the shared `en.json`) instead.
 *
 * Literals nested in a **conditional** are flagged too — `Text(if (x) "A" else "B")`,
 * `when` branches, and an elvis fallback (`Text(t ?: "Untitled")`) — because each branch is
 * itself a user-facing string. Matching only a bare literal argument is what let the player ship
 * `Text(if (state.hasMultipleAuthors) "Go to Author…" else "Go to Author")` un-localizable with
 * this rule green.
 *
 * The descent is deliberately limited to conditionals rather than every nested expression: a
 * blanket descent would flag data arguments such as `stringResource(Res.string.greeting, "Bob")`,
 * where the literal is a format value, not UI copy.
 *
 * Not flagged: `stringResource(...)` / identifier / call-expression arguments;
 * `testTag`/semantics keys; punctuation-only literals (`Text("•")`); and literals
 * inside `@Preview` functions (dev-only demo code, not shipped UI).
 */
class NoHardcodedUiStringRule(
    config: Config,
) : Rule(
        config,
        description = "User-facing UI strings must come from stringResource(Res.string.…), not literals.",
    ) {
    override val ruleName = RuleName("NoHardcodedUiString")

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text

        // contentDescription = "..." in ANY call (Icon, Image, IconButton, …)
        expression.valueArguments
            .firstOrNull { it.getArgumentName()?.asName?.asString() == "contentDescription" }
            ?.let { flagIfHardcoded(it, "contentDescription") }

        // Text("...") positional or Text(text = "...")
        if (callee == "Text") {
            val textArg =
                expression.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == "text" }
                    ?: expression.valueArguments.firstOrNull { it.getArgumentName() == null }
            textArg?.let { flagIfHardcoded(it, "Text") }
        }
    }

    private fun flagIfHardcoded(
        argument: KtValueArgument,
        context: String,
    ) {
        // Dev-only demo code.
        if (argument.getParentOfType<KtNamedFunction>(strict = true)?.isInsidePreview() == true) return
        userFacingLiterals(argument.getArgumentExpression())
            // Decorative: no letters in the *literal* parts (pure punctuation/symbols).
            .filter { template -> template.hasLetterInLiteralParts() }
            .forEach { template ->
                report(
                    Finding(
                        Entity.from(template),
                        "Hardcoded $context string. Move it to en.json and use stringResource(Res.string.…).",
                    ),
                )
            }
    }

    /**
     * Whether the template's own literal text contains a letter.
     *
     * Checks the literal entries rather than the whole template's source: `"${'$'}greeting,"` is an
     * interpolation plus a comma — no authored copy — but its *source text* contains the letters of
     * the identifier, so a naive `text.none { it.isLetter() }` reads it as a hardcoded string.
     * `"Show all ${'$'}n chapters"` still has authored literal parts and is flagged.
     */
    private fun KtStringTemplateExpression.hasLetterInLiteralParts(): Boolean =
        entries
            .filterIsInstance<KtLiteralStringTemplateEntry>()
            .any { entry -> entry.text.any { it.isLetter() } }

    /**
     * The string literals [expression] can evaluate to, unwrapping conditionals so every branch is
     * checked. Anything else (an identifier, a `stringResource(...)` call, a nested call's
     * arguments) yields nothing — see the class KDoc for why the descent stops at conditionals.
     */
    private fun userFacingLiterals(expression: KtExpression?): List<KtStringTemplateExpression> =
        when (expression) {
            null -> {
                emptyList()
            }

            is KtStringTemplateExpression -> {
                listOf(expression)
            }

            is KtParenthesizedExpression -> {
                userFacingLiterals(expression.expression)
            }

            is KtIfExpression -> {
                userFacingLiterals(expression.then) + userFacingLiterals(expression.`else`)
            }

            is KtWhenExpression -> {
                expression.entries.flatMap { userFacingLiterals(it.expression) }
            }

            is KtBinaryExpression -> {
                if (expression.operationToken == KtTokens.ELVIS) {
                    userFacingLiterals(expression.left) + userFacingLiterals(expression.right)
                } else {
                    emptyList()
                }
            }

            else -> {
                emptyList()
            }
        }

    private fun KtNamedFunction.isInsidePreview(): Boolean =
        annotationEntries.any { it.shortName?.asString() == "Preview" } ||
            getParentOfType<KtNamedFunction>(strict = true)?.isInsidePreview() == true
}
