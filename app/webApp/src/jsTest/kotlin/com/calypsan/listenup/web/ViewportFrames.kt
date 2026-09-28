package com.calypsan.listenup.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import com.calypsan.listenup.web.design.WebAppSurface
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.css.CSSStyleDeclaration
import org.w3c.dom.css.CSSStyleSheet
import org.w3c.dom.DOMRect
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLIFrameElement
import org.w3c.dom.Window
import org.w3c.dom.asList

/**
 * A composition rendered at a chosen viewport width, with the real design sheet applied.
 *
 * The runner's page is a fixed 1280px wide, and a page cannot resize its own viewport — so every
 * spec that mounts into `document.body` sees the desktop layout and nothing else. An `<iframe>` has
 * a viewport of its own: its media queries answer to ITS width, its `position:fixed` pins to ITS
 * edges, and layout inside it is real. That is what makes "at 320px nothing scrolls sideways" a
 * measurement rather than a hope.
 *
 * The frame's sheet is the runner page's own CSSOM, copied rule for rule, so a phone spec reads the
 * same stylesheet the desktop specs do. The composition itself still runs in this window — only
 * its nodes live in the frame's document — so element wrappers and recomposition behave exactly as
 * they do for [MountRegistry].
 */
internal class ViewportFrames {
    private val mounted = mutableListOf<Pair<HTMLIFrameElement, Composition>>()

    /** Renders [content] (inside the app surface) into a fresh [width]×[height] frame. */
    fun mount(
        width: Int,
        height: Int = DEFAULT_HEIGHT,
        content: @Composable () -> Unit,
    ): ViewportFrame {
        val frame = document.createElement("iframe") as HTMLIFrameElement
        frame.style.cssText = "width:${width}px;height:${height}px;border:0;display:block"
        document.body!!.appendChild(frame)

        val frameDocument = frame.contentDocument!!
        val style = frameDocument.createElement("style")
        style.textContent = runnerCss()
        frameDocument.head!!.appendChild(style)
        frameDocument.body!!.setAttribute("style", "margin:0")

        // Created in THIS document and adopted by the frame's on append, so the wrapper keeps this
        // window's prototypes and every `as HTMLElement` in the code under test still holds.
        val host = document.createElement("div") as HTMLElement
        frameDocument.body!!.appendChild(host)
        mounted += frame to renderComposable(root = host) { WebAppSurface { content() } }
        return ViewportFrame(host, frame.contentWindow!!, width, height)
    }

    /** Disposes every composition and removes its frame. Safe to call twice. */
    fun disposeAll() {
        mounted.forEach { (frame, composition) ->
            composition.dispose()
            frame.remove()
        }
        mounted.clear()
    }

    private companion object {
        /** A phone held upright — tall enough that a bottom bar and a player bar leave content room. */
        const val DEFAULT_HEIGHT = 640
    }
}

/** One mounted frame: where the content landed, and the window whose viewport it answers to. */
internal class ViewportFrame(
    val host: HTMLElement,
    val window: Window,
    val width: Int,
    val height: Int,
) {
    /** The element matching [selector], or a failure naming the selector. */
    fun find(selector: String): HTMLElement =
        host.ownerDocument!!.querySelector(selector) as? HTMLElement
            ?: error("nothing matches '$selector' in the ${width}px frame")

    /** Every element matching [selector], anywhere in the frame — dialogs included. */
    fun findAll(selector: String): List<HTMLElement> =
        host.ownerDocument!!
            .querySelectorAll(selector)
            .asList()
            .filterIsInstance<HTMLElement>()

    /** The computed style, evaluated against THIS frame's viewport. */
    fun style(element: Element): CSSStyleDeclaration = window.getComputedStyle(element)

    /** One computed property by its CSS name — `position`, `scroll-padding-bottom`. */
    fun css(
        element: Element,
        property: String,
    ): String = style(element).getPropertyValue(property)

    /** Rendered and taking up space: not `display:none` anywhere up the tree. */
    fun isShown(element: HTMLElement): Boolean = element.getClientRects().length > 0

    fun rect(element: Element): DOMRect = element.getBoundingClientRect()

    /** How far the frame's page scrolls sideways. Zero is the only right answer on a phone. */
    fun horizontalOverflow(): Int {
        val root = host.ownerDocument!!.documentElement!!
        return root.scrollWidth - root.clientWidth
    }
}

/** Every rule the runner page has loaded, as one sheet. */
private fun runnerCss(): String {
    val sheets = document.styleSheets
    return (0 until sheets.length)
        .mapNotNull { sheets.item(it) as? CSSStyleSheet }
        .joinToString("\n") { sheet ->
            val rules = sheet.cssRules
            (0 until rules.length).joinToString("\n") { rules.item(it)?.cssText.orEmpty() }
        }
}
