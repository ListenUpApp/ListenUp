package com.calypsan.listenup.web.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.css.CSSStyleSheet

/**
 * Guards that a keyboard reader can always see where focus is.
 *
 * Removing the browser's outline on `:focus` is how a click avoids leaving a ring behind, and it
 * is only safe when `:focus-visible` puts a ring back. `.home-card` removed the outline and never
 * restored it, so the first row on Home was tabbable and showed nothing at all. Separately, the
 * global ring once set its own `border-radius`, which turned every round control into a rounded
 * square while it held focus.
 *
 * Read off the LOADED stylesheet rather than the source text, for the same reason
 * [PressContractTest] does.
 */
class FocusContractTest :
    FunSpec({

        /** One selector of one rule, with whether its declarations remove the outline. */
        data class FocusRule(
            val selector: String,
            val removesOutline: Boolean,
            val radius: String,
        )

        fun allRules(): List<FocusRule> {
            val found = mutableListOf<FocusRule>()

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
                    val removes = (rule.style.outlineStyle as? String) == "none"
                    val radius = (rule.style.borderRadius as? String).orEmpty()
                    selectorText.split(",").forEach { found += FocusRule(it.trim(), removes, radius) }
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

        val plainFocus = Regex(""":focus(?![-\w])""")

        /** The element a `:focus` selector is about: everything before the pseudo-class. */
        fun removedSubjects(): Set<String> =
            allRules()
                .filter { it.removesOutline && plainFocus.containsMatchIn(it.selector) }
                .map { it.selector.substringBefore(":focus").trim() }
                .toSet()

        fun replacedSubjects(): Set<String> =
            allRules()
                .filter { it.selector.contains(":focus-visible") || it.selector.contains(":focus-within") }
                .map { it.selector.substringBefore(":focus-").trim() }
                .toSet()

        test("the sheets are actually loaded, or this whole spec is vacuous") {
            removedSubjects().contains(".lib-card") shouldBe true
        }

        test("anything that removes its focus outline says what replaces it") {
            // Joined into one string rather than compared as a list: a list assertion truncates at
            // twenty entries, and the names are exactly what someone fixing this needs.
            val unreplaced = (removedSubjects() - replacedSubjects() - FOCUS_EXEMPT).sorted()

            unreplaced.joinToString("\n") shouldBe ""
        }

        test("the exemption list has no dead entries") {
            val removed = removedSubjects()
            val dead = FOCUS_EXEMPT.filterNot { it in removed }.sorted()

            dead.joinToString("\n") shouldBe ""
        }

        test("the global focus ring does not reshape what it rings") {
            val ring = allRules().filter { it.selector == ".luw :focus-visible" }

            ring.size shouldBe 1
            // A browser draws the outline along the element's own radius; forcing one here turns
            // round buttons into rounded squares for as long as they hold focus.
            ring.single().radius shouldBe ""
        }
    })

/** Outline removals with no `:focus-visible` twin, each for a stated reason. */
private val FOCUS_EXEMPT =
    setOf(
        // Programmatic focus targets: PageFocus moves focus here after a route change, and they are
        // never tabbed to, so a ring would only flash at a reader who did nothing.
        ".shell-main",
        ".shell-main h1",
        // Swaps to a coral border on plain :focus, which is the indicator.
        ".rel-seq",
        // The ring is drawn on the whole card by `.perm-card:has(input:focus-visible)`.
        ".luw .perm-card input",
    )
