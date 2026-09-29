package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.ViewportFrames
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.css.CSSStyleSheet

/**
 * Type follows the reader's own font size.
 *
 * A browser's default font size is an accessibility setting — people with low vision raise it
 * rather than zooming every page. A `font-size` in px ignores it; in rem it scales with it. So the
 * sheet sizes text in rem, at the same computed size as before against the default 16px root.
 *
 * The exceptions are text drawn INSIDE a fixed-size shape, where growing the text would break the
 * shape: the skip button's interval numeral sits inside the arrow glyph, and the unread badge is a
 * pill sized to its count.
 */
class RemFontSizesTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        test("no sheet sizes text in px, bar the fixed-shape exceptions") {
            val declarations = fontSizeDeclarations()

            declarations.shouldNotBeEmpty()
            declarations
                .filter { (_, size) -> size.endsWith("px") }
                .filterNot { (selector, _) -> PX_ALLOWED.any { it in selector } }
                .shouldBeEmpty()
        }

        test("a reader who raises their default font size gets larger interface text") {
            val frame =
                frames.mount(390) {
                    Div(attrs = { classes("menu") }) { Button(attrs = { classes("menu-i") }) { Text("Open") } }
                }
            val item = frame.find(".menu-i")
            val atDefault = frame.css(item, "font-size")

            frame.host.ownerDocument!!
                .documentElement!!
                .asDynamic()
                .style.fontSize = "20px"

            atDefault shouldBe "13.5px"
            frame.css(item, "font-size") shouldBe "16.875px"
        }
    })

/** Selectors whose text lives inside a fixed-px shape (see the class KDoc). */
private val PX_ALLOWED = listOf(".tport-skip-n", ".nav-badge")

/** Every `font-size` in every loaded sheet, as (selector, specified value). */
private fun fontSizeDeclarations(): List<Pair<String, String>> {
    val found = mutableListOf<Pair<String, String>>()

    fun collect(rules: dynamic) {
        val length = rules.length as? Int ?: return
        for (i in 0 until length) {
            val rule: dynamic = rules.item(i)
            val selector = rule.selectorText as? String
            if (selector != null) {
                val size = (rule.style.getPropertyValue("font-size") as? String).orEmpty()
                if (size.isNotBlank()) found += selector to size
            }
            val nested = rule.cssRules
            if (nested != null && selector == null) collect(nested)
        }
    }

    val sheets = document.styleSheets
    for (i in 0 until sheets.length) {
        val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
        collect(runCatching { sheet.cssRules }.getOrNull()?.asDynamic() ?: continue)
    }
    return found
}
