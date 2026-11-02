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
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Forbids hardcoded user-facing string literals in Compose UI: a literal passed as the named
 * argument `text`, `title`, `message`, `label`, `placeholder` or `contentDescription` to any call
 * (`ListenUpButton(text = "Retry")`, `AlertDialog(title = "…")`), and as the first positional
 * argument of `Text(...)`, `showSnackbar(...)` and `shareText(...)`. Use
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
 * `label` on an animation API (`animate*AsState`, `AnimatedContent`, `Crossfade`, …) is a tooling name,
 * not copy, and files named `*Preview.kt` / `*Previews.kt` / `*PreviewGallery.kt` are dev-only. Which
 * modules the rule applies to is configured in `detekt.yml` (`excludes`).
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

        // contentDescription / text / title / message / label / placeholder = "..." in ANY call
        // (Icon, Image, ListenUpButton, dialogs, …)
        expression.valueArguments
            .filter { it.getArgumentName()?.asName?.asString() in COPY_ARGUMENT_NAMES }
            .filterNot { it.getArgumentName()?.asName?.asString() == "label" && callee.isAnimationApi() }
            .forEach {
                flagIfHardcoded(
                    it,
                    it
                        .getArgumentName()
                        ?.asName
                        ?.asString()
                        .orEmpty(),
                )
            }

        // Text("...") / showSnackbar("...") / shareText("...", url): the first positional argument is copy.
        if (callee in FIRST_ARGUMENT_COPY_CALLEES) {
            expression.valueArguments
                .firstOrNull { it.getArgumentName() == null }
                ?.let { flagIfHardcoded(it, callee.orEmpty()) }
        }
    }

    private fun flagIfHardcoded(
        argument: KtValueArgument,
        context: String,
    ) {
        // Dev-only demo code.
        if (argument.containingKtFile.isPreviewFile()) return
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

    /** Animation APIs take a `label` that is a debugging name for tooling, never shown to a user. */
    private fun String?.isAnimationApi(): Boolean = this != null && (startsWith("animate") || this in ANIMATION_CALLEES)

    private fun KtFile.isPreviewFile(): Boolean = PREVIEW_FILE_SUFFIXES.any { name.endsWith(it) }

    private companion object {
        val ANIMATION_CALLEES =
            setOf(
                "AnimatedContent",
                "AnimatedVisibility",
                "Crossfade",
                "updateTransition",
                "rememberInfiniteTransition",
                "rememberTransition",
                "rememberAmbientLoop",
            )
        val PREVIEW_FILE_SUFFIXES = listOf("Preview.kt", "Previews.kt", "PreviewGallery.kt")
        val COPY_ARGUMENT_NAMES = setOf("contentDescription", "text", "title", "message", "label", "placeholder")
        val FIRST_ARGUMENT_COPY_CALLEES = setOf("Text", "showSnackbar", "shareText")
    }

    private fun KtNamedFunction.isInsidePreview(): Boolean =
        annotationEntries.any { it.shortName?.asString() == "Preview" } ||
            getParentOfType<KtNamedFunction>(strict = true)?.isInsidePreview() == true
}
