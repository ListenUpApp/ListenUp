package com.calypsan.listenup.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Forbids hardcoded user-facing string literals in Compose UI: `Text("literal")`,
 * `Text(text = "literal")`, and `contentDescription = "literal"`. Use
 * `stringResource(Res.string.…)` (backed by the shared `en.json`) instead.
 *
 * Not flagged: `stringResource(...)` / identifier / call-expression arguments;
 * `testTag`/semantics keys; punctuation-only literals (`Text("•")`); and literals
 * inside `@Preview` functions (dev-only demo code, not shipped UI).
 */
class NoHardcodedUiStringRule(
    config: Config,
) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = "NoHardcodedUiString",
            severity = Severity.Defect,
            description = "User-facing UI strings must come from stringResource(Res.string.…), not literals.",
            debt = Debt.FIVE_MINS,
        )

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
        val template = argument.getArgumentExpression() as? KtStringTemplateExpression ?: return
        // Decorative: a literal with no letters (pure punctuation/symbols).
        if (template.text.none { it.isLetter() }) return
        // Dev-only demo code.
        if (argument.getParentOfType<KtNamedFunction>(strict = true)?.isInsidePreview() == true) return
        report(
            CodeSmell(
                issue,
                Entity.from(argument),
                "Hardcoded $context string. Move it to en.json and use stringResource(Res.string.…).",
            ),
        )
    }

    private fun KtNamedFunction.isInsidePreview(): Boolean =
        annotationEntries.any { it.shortName?.asString() == "Preview" } ||
            getParentOfType<KtNamedFunction>(strict = true)?.isInsidePreview() == true
}
