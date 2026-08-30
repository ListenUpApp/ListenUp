package com.calypsan.listenup.web.design

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.w3c.dom.css.CSSStyleSheet

/**
 * Guards that no hover treatment reaches a device that cannot hover.
 *
 * On a touchscreen there is no pointer, and a browser resolves that by applying `:hover` on tap and
 * **leaving it applied** until something else is tapped. The result is a card, row or nav item that
 * stays lit long after the reader has moved on — a highlight nobody asked for, pointing at nothing.
 * That is worse than no feedback at all, because it is feedback that lies about the current state.
 *
 * `@media (hover: hover)` is the platform's own answer: the styling applies only where a pointer
 * that can actually hover exists. Touch then relies on the press vocabulary instead — see
 * [PressContractTest], which is the other half of this and pins that every hover treatment has a
 * press twin. Together they say: on a pointer you get both, on a finger you get the press.
 *
 * ⛔ **Wrapping is not always the right move, and the guard cannot tell the difference.** A rule
 * whose selector list mixes `:hover` with `:focus-visible` (or a state class like `.cover-drag`)
 * must be SPLIT rather than wrapped — keyboard focus and drag-over are not pointer states, and
 * gating them would take them away from exactly the readers who depend on them. Three rules in this
 * sheet needed splitting; each carries a comment saying so.
 */
class HoverGatingContractTest :
    FunSpec({

        /**
         * Every `:hover` selector in the sheet, paired with whether an enclosing `@media` condition
         * mentions hover capability.
         */
        fun hoverRules(): List<Pair<String, Boolean>> {
            val found = mutableListOf<Pair<String, Boolean>>()

            fun collect(
                rules: dynamic,
                gated: Boolean,
            ) {
                val length = rules.length as? Int ?: return
                for (i in 0 until length) {
                    // Typed `dynamic` and read with a plain dot, deliberately. Writing
                    // `rule?.selectorText` smart-casts `rule` off `dynamic` inside the null branch,
                    // and every later property on it then either fails to compile or — with
                    // `asDynamic()` bolted on — emits a real method call that blows up at runtime
                    // with "rule.asDynamic is not a function". Dynamic tolerates a plain dot on a
                    // null-ish value, so there is nothing to guard against.
                    val rule: dynamic = rules.item(i)
                    val selectorText = rule.selectorText as? String
                    if (selectorText == null) {
                        // Only grouping rules (`@media`, `@supports`) carry children and a
                        // condition; anything else here is a rule type this guard does not read.
                        val nested = rule.cssRules
                        if (nested != null) {
                            val condition = (rule.conditionText as? String).orEmpty()
                            collect(nested, gated || "hover" in condition)
                        }
                        continue
                    }
                    selectorText.split(",").forEach { one ->
                        val trimmed = one.trim()
                        if (":hover" in trimmed) found += trimmed to gated
                    }
                }
            }

            val sheets = document.styleSheets
            for (i in 0 until sheets.length) {
                val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
                val rules = runCatching { sheet.cssRules }.getOrNull() ?: continue
                collect(rules.asDynamic(), gated = false)
            }
            return found
        }

        test("web.css is actually loaded, or this whole spec is vacuous") {
            hoverRules().isNotEmpty() shouldBe true
        }

        test("no hover treatment reaches a device that cannot hover") {
            val ungated =
                hoverRules()
                    .filterNot { (_, gated) -> gated }
                    .map { (selector, _) -> selector }
                    .filterNot { it in HOVER_GATING_EXEMPT }
                    .distinct()
                    .sorted()

            // Joined rather than compared as a list: a list assertion truncates, and the names are
            // the whole point of the failure.
            ungated.joinToString("\n") shouldBe ""
        }

        test("the exemption list has no dead entries") {
            val present = hoverRules().map { it.first }.toSet()
            val dead = HOVER_GATING_EXEMPT.filterNot { it in present }.sorted()

            dead.joinToString("\n") shouldBe ""
        }
    })

/**
 * Hover selectors that are deliberately NOT gated, with the reason.
 *
 * The single entry cancels a treatment rather than applying one. Under
 * `prefers-reduced-motion: reduce` the library cover's lift is set back to `none`, and a
 * cancellation has to reach every device that could have received the thing being cancelled —
 * gating it would be reasoning about the wrong condition entirely.
 */
private val HOVER_GATING_EXEMPT = setOf(".lib-card:hover .lib-cover")
