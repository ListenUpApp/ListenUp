package com.calypsan.listenup.web.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.css.CSSStyleSheet

/**
 * Guards that anything answering a mouse also answers a finger.
 *
 * `web.css` grew 58 `:hover` rules and exactly one `:active` rule. On a pointer that is merely a
 * shortfall; on a touchscreen there IS no hover, so a tablet reader got no acknowledgement at all
 * from any card, row, tab, nav item or chip — the tap either did something or the page looked
 * broken. The rule this pins is both the fix and what keeps it fixed: a new hover treatment
 * cannot be added without deciding what its press looks like.
 *
 * Read off the LOADED stylesheet rather than the source text, for the same reason
 * [ClassContractTest] does: it is the rules the browser actually has that matter, and a selector
 * inside `@media` carries no `selectorText` at the top level.
 */
class PressContractTest :
    FunSpec({

        /**
         * Reduces a subject to the thing being pressed, dropping refinements that do not change
         * which control the treatment is about.
         *
         * Two of them, both load-bearing. A `[data-theme="dark"]` prefix names a palette, not a
         * different control — `.bulk-b` presses identically in either theme, because the press is
         * a transform and a transform has no colour. And `:not(...)` narrows which instances get
         * the treatment: `.search-row:not(.is-static)` IS the press treatment for `.search-row`,
         * since the excluded instances are exactly the ones deliberately left inert. Without this
         * the guard would demand a press rule that already exists under a slightly different name.
         */
        fun normalise(subject: String): String =
            subject
                .replace(Regex("""^\[data-theme=[^\]]*\]\s*"""), "")
                .replace(Regex(""":not\([^)]*\)"""), "")
                .trim()

        /**
         * The elements a pseudo-class is attached to, across every loaded sheet.
         *
         * `.lib-card:hover .lib-title` is a hover on `.lib-card` that paints a descendant, so its
         * subject is `.lib-card` — and `.lib-card:active` is what satisfies it. Taking the whole
         * selector would demand a nonsensical `.lib-card:active .lib-title` twin.
         */
        fun subjectsOf(pseudo: String): Set<String> {
            val subjects = mutableSetOf<String>()

            fun collect(rules: dynamic) {
                val length = rules.length as? Int ?: return
                for (i in 0 until length) {
                    val rule = rules.item(i)
                    val selectorText = rule?.selectorText as? String
                    if (selectorText == null) {
                        // @media / @supports and friends: the selectors live one level down.
                        val nested = rule?.cssRules
                        if (nested != null) collect(nested)
                        continue
                    }
                    selectorText.split(",").forEach { one ->
                        val trimmed = one.trim()
                        if (trimmed.contains(pseudo)) {
                            subjects += normalise(trimmed.substringBefore(pseudo).trim())
                        }
                    }
                }
            }

            val sheets = document.styleSheets
            for (i in 0 until sheets.length) {
                val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
                val rules = runCatching { sheet.cssRules }.getOrNull() ?: continue
                collect(rules.asDynamic())
            }
            return subjects
        }

        test("web.css is actually loaded, or this whole spec is vacuous") {
            subjectsOf(":hover").contains(".btn") shouldBe true
        }

        test("everything that answers a mouse also answers a finger") {
            // Joined into one string rather than compared as a list: a list assertion truncates at
            // twenty entries, and "…and 28 more" is precisely the part someone fixing this needs.
            val unpressable = (subjectsOf(":hover") - subjectsOf(":active") - PRESS_EXEMPT).sorted()

            unpressable.joinToString("\n") shouldBe ""
        }

        test("the exemption list has no dead entries") {
            // An exemption for a selector that no longer carries a hover treatment is a claim
            // nobody checks any more, and it hides the next real gap behind a stale name.
            val hover = subjectsOf(":hover")
            val dead = PRESS_EXEMPT.filterNot { it in hover }.sorted()

            dead.joinToString("\n") shouldBe ""
        }
    })

/**
 * Hover treatments that deliberately have no press twin, each for a stated reason.
 *
 * Keep this short and keep the reasons concrete. "It looked fine" is not a reason; the whole point
 * of the guard is that the decision gets made rather than skipped.
 */
private val PRESS_EXEMPT =
    setOf(
        // Inline text links. Their hover IS the affordance — an underline appearing — and text
        // that shifts a pixel under the finger reads as a layout bug rather than as a button.
        ".lnk",
        ".crumb a",
        ".bd-by-name",
        // Already in its pressed state: a selected row is painted coral, and its hover rule exists
        // only to stop the ordinary row hover from overriding that.
        ".tbl tbody tr.sel",
        // Deliberately inert — a static row in the palette is a heading, not a target. Its hover
        // rule exists to CANCEL the row hover above it.
        ".search-row.is-static",
        // Already pressed by `.menu-i:active`, which matches these elements too. A rule of its own
        // would be the identical declaration written twice.
        ".menu-i.danger",
    )
