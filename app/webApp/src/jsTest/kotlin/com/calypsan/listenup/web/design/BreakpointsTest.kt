package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.ViewportFrames
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.css.CSSStyleSheet

/**
 * The sheet's four breakpoints, and nothing else: 640, 760, 1024 and 1280 (documented at the top
 * of `00-base.css`). Every width query is written against one of them — `max-width` one pixel
 * below, `min-width` on it — so two rules that mean "a phone" cannot disagree about where a phone
 * ends. There were eight: 620, 640, 759, 1023, 1100, 1180, 1279, and a page that was a phone to one
 * rule and a tablet to the next.
 */
class BreakpointsTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        test("every width query in the sheet is one of the four breakpoints") {
            val widths = widthConditions()

            widths.shouldNotBeEmpty()
            widths.filterNot { it in ALLOWED }.shouldBeEmpty()
        }

        /** [probe]'s computed [property] in a frame of [width]. */
        fun at(
            width: Int,
            property: String,
            probe: String,
            content: @Composable () -> Unit,
        ): String {
            val frame = frames.mount(width, height = 400) { content() }
            return frame.css(frame.find(probe), property)
        }

        test("the sign-in brand panel appears at 1024, not before") {
            val auth = @Composable {
                Div(attrs = { classes("auth") }) { Div(attrs = { classes("auth-brand") }) }
            }

            at(1023, "display", ".auth-brand", auth) shouldBe "none"
            at(1024, "display", ".auth-brand", auth) shouldBe "flex"
        }

        test("the chapter editor puts the list beside the timeline at 1280, not before") {
            val editor = @Composable {
                Div(attrs = { classes("ched") }) { Div(attrs = { classes("ched-body") }) }
            }

            at(1279, "display", ".ched-body", editor) shouldBe "flex"
            at(1280, "display", ".ched-body", editor) shouldBe "grid"
        }

        test("the full player stacks its cover above the controls below 640") {
            val player = @Composable { Div(attrs = { classes("np-body") }) }

            at(639, "flex-direction", ".np-body", player) shouldBe "column"
            at(640, "flex-direction", ".np-body", player) shouldBe "row"
        }
    })

private val ALLOWED =
    setOf("max-width: 639px", "max-width: 759px", "max-width: 1023px", "max-width: 1279px") +
        setOf("min-width: 640px", "min-width: 760px", "min-width: 1024px", "min-width: 1280px")

private val WIDTH_CONDITION = Regex("""(max|min)-width:\s*[0-9.]+px""")

/** Every `(max-width: …)` / `(min-width: …)` in every loaded sheet, as written. */
private fun widthConditions(): List<String> {
    val found = mutableListOf<String>()

    fun collect(rules: dynamic) {
        val length = rules.length as? Int ?: return
        for (i in 0 until length) {
            val rule: dynamic = rules.item(i)
            val condition = rule.conditionText as? String
            // A container query measures a component's own width, not the viewport's, so the
            // viewport's breakpoints do not apply to it (the ratings panel splits at 560px of itself).
            val isContainerQuery = rule.containerQuery != null
            if (condition != null && !isContainerQuery) {
                WIDTH_CONDITION.findAll(condition).forEach { found += it.value }
            }
            val nested = rule.cssRules
            if (nested != null) collect(nested)
        }
    }

    val sheets = document.styleSheets
    for (i in 0 until sheets.length) {
        val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
        collect(runCatching { sheet.cssRules }.getOrNull()?.asDynamic() ?: continue)
    }
    return found
}
