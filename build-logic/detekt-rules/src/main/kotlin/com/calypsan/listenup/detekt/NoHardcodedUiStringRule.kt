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
 * Forbids hardcoded user-facing string literals in Compose UI. Use
 * `stringResource(Res.string.…)` (backed by the shared `en.json`) instead.
 *
 * Coverage is **param-name-based**, so it catches custom text-bearing components
 * automatically — not just `Text`. Any call argument whose named parameter is one
 * of `text`, `title`, `label`, `placeholder`, `subtitle`, `headline`, `confirmText`,
 * `dismissText`, or `contentDescription` is flagged when it's a hardcoded literal.
 * This means `ListenUpButton(text = "Save")`, `SectionTitle(title = "…")`, a dialog's
 * `confirmText`/`dismissText`, and a field's `label`/`placeholder` are all caught,
 * alongside `Text(text = "literal")` and `contentDescription = "literal"`. The
 * positional `Text("literal")` form is also flagged.
 *
 * Not flagged: `stringResource(...)` / identifier / call-expression arguments;
 * `testTag`/semantics keys; punctuation-only literals (`Text("•")`); and literals
 * inside `@Preview` functions (dev-only demo code, not shipped UI).
 */
class NoHardcodedUiStringRule(
    config: Config,
) : Rule(config) {
    private companion object {
        // Named args that carry user-facing UI text in this Compose codebase.
        val UI_TEXT_ARG_NAMES =
            setOf(
                "contentDescription",
                "text",
                "title",
                "label",
                "placeholder",
                "subtitle",
                "headline",
                "confirmText",
                "dismissText",
            )
    }

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

        // Any UI-text named arg in ANY call: text, title, label, placeholder,
        // subtitle, headline, confirmText, dismissText, contentDescription. This
        // covers Text(text = …), custom components (ListenUpButton(text = …)),
        // dialogs (confirmText/dismissText), fields (label/placeholder), etc.
        for (argument in expression.valueArguments) {
            val name = argument.getArgumentName()?.asName?.asString() ?: continue
            if (name in UI_TEXT_ARG_NAMES) flagIfHardcoded(argument, name)
        }

        // Text("...") positional only — the named text= case is handled above, so
        // requiring an UNNAMED first arg here avoids double-flagging.
        if (callee == "Text") {
            expression.valueArguments
                .firstOrNull { it.getArgumentName() == null }
                ?.let { flagIfHardcoded(it, "Text") }
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
