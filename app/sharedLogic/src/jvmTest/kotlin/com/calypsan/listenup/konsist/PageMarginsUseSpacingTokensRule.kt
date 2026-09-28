package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Feature screens name their page margins with `Spacing` tokens instead of raw dp.
 *
 * DESIGN.md's layout rhythm lives in `design/theme/Spacing.kt`: a 24dp `screenMargin`, a 24dp
 * `sectionGap`, and a 4dp-based scale (`xs`…`xxl`) for everything else. The 2026-09-27 Android audit
 * found the tokens in 7 of 32 features against 143 raw `padding(16|24.dp)` margins, so a retune of
 * the page margin reached almost nothing, and sibling screens had drifted apart (Discover at 16dp
 * beside Home and Library at 24dp). The margins were migrated; this rule stops them drifting back.
 *
 * It bans the *page-margin shapes* with a 16 or 24dp literal, not all dp: a `padding(` or
 * `PaddingValues(` call that is all-sides 16/24dp, names `horizontal = 16|24.dp`, or pairs
 * `start` and `end` at the same 16/24dp. Icon, cover and touch-target sizes, strokes, radii and
 * other paddings are left alone — they are geometry, or off-scale on purpose.
 *
 * [PAGE_MARGIN_ALLOWLIST] exempts a file whose margin is genuinely geometry (a value derived from a
 * drawing, not a layout rhythm), keyed by path suffix; each entry must say why.
 * Comments are skipped, so KDoc may still quote a raw margin.
 */
class PageMarginsUseSpacingTokensRule :
    FunSpec({
        test("no feature file writes a page margin as a raw 16 or 24dp literal") {
            val featureFiles =
                productionScope()
                    .files
                    .filter { "/app/sharedUI/" in it.path && "/features/" in it.path }

            assertScopeNotEmpty(
                featureFiles,
                expectedMin = 200,
                why = "every :app:sharedUI features/ production file — the screens whose margins this pins",
            )

            featureFiles
                .filter { file -> PAGE_MARGIN_ALLOWLIST.keys.none { file.path.endsWith(it) } }
                .flatMap { file -> rawPageMarginLines(file.text).map { line -> "${file.path}:$line" } }
                .shouldBeEmpty()
        }
    })

/**
 * Files allowed to keep a raw page-margin literal, keyed by path suffix, valued by the reason.
 * Empty at introduction: every page-margin-shaped literal in features/ was a layout rhythm, and
 * none was geometry. Add an entry only with a reason a reviewer would accept.
 */
internal val PAGE_MARGIN_ALLOWLIST: Map<String, String> = emptyMap()

private val MARGIN_CALL = Regex("""\b(padding|PaddingValues)\(""")
private val ALL_SIDES = Regex("""^\s*(16|24)\.dp\s*$""")
private val HORIZONTAL = Regex("""\bhorizontal\s*=\s*(16|24)\.dp\b""")
private val START = Regex("""\bstart\s*=\s*(16|24)\.dp\b""")
private val END = Regex("""\bend\s*=\s*(16|24)\.dp\b""")

/**
 * 1-based line of every page-margin-shaped `padding(`/`PaddingValues(` call in [text] that writes a
 * 16 or 24dp literal. Calls may span lines; comments are blanked first so KDoc quoting a raw
 * margin is not a finding.
 */
internal fun rawPageMarginLines(text: String): List<Int> {
    val code = blankComments(text)
    return MARGIN_CALL
        .findAll(code)
        .mapNotNull { call ->
            val argsStart = call.range.last + 1
            val argsEnd = closingParen(code, argsStart) ?: return@mapNotNull null
            val args = code.substring(argsStart, argsEnd)
            val start = START.find(args)?.groupValues?.get(1)
            val end = END.find(args)?.groupValues?.get(1)
            val isMargin =
                ALL_SIDES.matches(args) || HORIZONTAL.containsMatchIn(args) || (start != null && start == end)
            if (isMargin) code.substring(0, call.range.first).count { it == '\n' } + 1 else null
        }.toList()
}

/** Index of the `)` closing the call whose arguments start at [from], or null if unbalanced. */
private fun closingParen(
    code: String,
    from: Int,
): Int? {
    var depth = 1
    for (i in from until code.length) {
        when (code[i]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return i
        }
    }
    return null
}

/**
 * Strings, char literals and comments, in source order. A string or char literal is matched first
 * so a `//` inside `"https://…"` or a `'"'` cannot be mistaken for the start of a comment or string.
 */
private val LITERAL_OR_COMMENT = Regex(""""(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\])*'|//[^\n]*|/\*[\s\S]*?\*/""")

/** [text] with `//` and block comments replaced by spaces, keeping newlines so line numbers hold. */
internal fun blankComments(text: String): String =
    LITERAL_OR_COMMENT.replace(text) { match ->
        val token = match.value
        if (token.startsWith("//") || token.startsWith("/*")) {
            token.replace(Regex("[^\n]"), " ")
        } else {
            token
        }
    }
