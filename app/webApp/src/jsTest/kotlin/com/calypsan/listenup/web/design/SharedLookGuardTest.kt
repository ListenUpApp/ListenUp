package com.calypsan.listenup.web.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.css.CSSStyleSheet

/**
 * The sheet-side half of the shared-components guard (the source-side half is Konsist's
 * `WebSharedComponentsRule`): no page stylesheet re-rolls a page title or an empty state.
 *
 * The 2026-09-28 audit found 31 private page-title rules drifting between 1.5rem and 2.1rem, and 21
 * private empty-state variants. Pass 5 replaced them with `.page-t` and `.empty`; these fail the
 * build if a page brings its own back. Read off the LOADED stylesheet, like [ClassContractTest].
 */
class SharedLookGuardTest :
    FunSpec({
        test("only the page header sets a page title's size") {
            // A title-sized font anywhere else is a page drawing its own title. The exceptions are
            // not titles: a numeral read at arm's length, and the sign-in panel's brand line.
            val offenders =
                declarations("font-size")
                    .filter { (_, value) -> isTitleSized(value) }
                    .map { (selector, _) -> selector }
                    .filterNot { it in TITLE_SIZED_ALLOWED }

            offenders.joinToString("\n") shouldBe ""
        }

        test("no sheet styles a private empty state") {
            val offenders =
                declarations(property = null)
                    .map { (selector, _) -> selector }
                    .filter { PRIVATE_EMPTY.containsMatchIn(it) }
                    .filterNot { selector -> PLACEHOLDERS.any { it in selector } }
                    .distinct()

            offenders.joinToString("\n") shouldBe ""
        }
    })

/** At or above the h1 step: a title, not a heading inside one. */
private fun isTitleSized(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed == "var(--fs-h1)" || trimmed == "var(--fs-display)") return true
    val rem = trimmed.removeSuffix("rem").toDoubleOrNull() ?: return false
    return trimmed.endsWith("rem") && rem >= TITLE_REM
}

private const val TITLE_REM = 1.5

private val TITLE_SIZED_ALLOWED =
    setOf(
        ".page-t",
        ".page-h.is-display .page-t",
        // Numerals read from arm's length, not headings: the speed and boost readouts, the sleep
        // countdown, and Home's week total.
        ".speed-read",
        ".boost-read",
        ".sleep-left",
        ".luw .home-stats-total",
        // Glyphs drawn large: a contributor's monogram, and the stars a rating is chosen with.
        ".cd-avatar",
        ".rs-input",
        // The sign-in screen's brand line, beside the page's own H1 — a poster, not a title.
        ".auth-hd",
    )

/** A class named for "nothing here": `.brw-none`, `.mdx-empty`… */
private val PRIVATE_EMPTY = Regex("""\.[a-z0-9]+(-[a-z0-9]+)*-(none|empty)\b""")

/** Image placeholders and states named `-none`/`-empty` that are not empty states. */
private val PLACEHOLDERS = listOf("photo-none", "cover-none", "result-none", ".is-empty")

/** (selector, value) for [property] in every loaded rule, or every selector when [property] is null. */
private fun declarations(property: String?): List<Pair<String, String>> {
    val found = mutableListOf<Pair<String, String>>()

    fun collect(rules: dynamic) {
        val length = rules.length as? Int ?: return
        for (i in 0 until length) {
            val rule: dynamic = rules.item(i)
            val selector = rule.selectorText as? String
            if (selector == null) {
                val nested = rule.cssRules
                if (nested != null) collect(nested)
                continue
            }
            val value = if (property == null) "" else (rule.style.getPropertyValue(property) as? String).orEmpty()
            if (property == null || value.isNotBlank()) {
                selector.split(",").forEach { found += it.trim() to value }
            }
        }
    }

    val sheets = document.styleSheets
    for (i in 0 until sheets.length) {
        val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
        val rules = runCatching { sheet.cssRules }.getOrNull() ?: continue
        collect(rules.asDynamic())
    }
    return found
}
