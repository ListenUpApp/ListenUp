package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.asList

/**
 * A region that keeps keyboard focus when a press replaces the control that took it.
 *
 * A browser drops focus to `<body>` when the focused element leaves the page, and the reader's next
 * Tab starts again from the skip link. That is what an in-place action does by nature: "Send 75
 * books" becomes a progress card, "Not now" becomes a quiet row, Save token becomes "Set · belongs to
 * @…". So after [key] changes, if focus was last inside this region and is now nowhere, it lands on
 * the region's [focusLanding] — the element that says what the press came to.
 *
 * Only a rescue, never a grab: focus that is somewhere (the reader moved on, or a dialog took it) is
 * left alone, and so is a region focus was never in — a page that loads in a later phase does not
 * steal focus from where the reader is.
 *
 * The wrapper is `display: contents`, so it draws nothing: wrap a page's column, or a form section,
 * without changing its layout.
 */
@Composable
fun FocusHold(
    key: Any?,
    attrs: (AttrsScope<HTMLDivElement>.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val hold = remember { Hold() }
    Div(attrs = {
        style { property("display", "contents") }
        attrs?.invoke(this)
        onFocusIn { hold.inside = true }
        onFocusOut { event ->
            val leaving = event.target as? Node
            val arriving = event.relatedTarget as? Node
            if (arriving != null) {
                if (hold.host?.contains(arriving) != true) hold.inside = false
            } else {
                // Focus went nowhere: the reader clicked blank page (the control is still here), or the
                // control itself left the page (it is not). Only the second is a rescue.
                window.setTimeout({ if (leaving?.isConnected == true) hold.inside = false }, 0)
            }
        }
        ref { element ->
            hold.host = element
            onDispose { hold.host = null }
        }
    }) { content() }

    LaunchedEffect(key) { hold.rescue() }
}

/**
 * Marks the element focus lands on when a [FocusHold] rescues it — or, on a wrapper around a field,
 * the field inside it. [priority] 1 wins over 2: a region whose phase has no landing of its own still
 * has somewhere sensible to fall back to.
 */
fun AttrsScope<*>.focusLanding(priority: Int = 1) {
    attr(FOCUS_LANDING, priority.toString())
}

/**
 * Focuses [element], first making it focusable by script (`tabindex="-1"`) if it is not already —
 * a heading or a status line, which is told about rather than pressed, stays out of the Tab order.
 */
fun focusAsLanding(element: HTMLElement) {
    if (!element.hasAttribute("tabindex") && !element.matches(NATIVELY_FOCUSABLE)) {
        element.setAttribute("tabindex", "-1")
    }
    element.focus()
}

/** Whether focus is nowhere in [element]'s document — on `<body>`, where a removed control leaves it. */
internal fun focusIsLost(element: HTMLElement): Boolean {
    val document = element.ownerDocument ?: return false
    val active = document.activeElement
    return active == null || active == document.body
}

private class Hold {
    var host: HTMLElement? = null
    var inside = false

    fun rescue() {
        val region = host ?: return
        if (!inside || !focusIsLost(region)) return
        val landing =
            region
                .querySelectorAll("[$FOCUS_LANDING]")
                .asList()
                .filterIsInstance<HTMLElement>()
                .minByOrNull { it.getAttribute(FOCUS_LANDING)?.toIntOrNull() ?: Int.MAX_VALUE }
                ?: return
        // A landing that wraps a field lands in the field: the next thing to do there is type.
        val field = if (landing.matches(NATIVELY_FOCUSABLE)) null else landing.querySelector("input, select, textarea")
        focusAsLanding(field as? HTMLElement ?: landing)
    }
}

private const val FOCUS_LANDING = "data-focus-landing"

private const val NATIVELY_FOCUSABLE = "a[href], button, input, select, textarea, [contenteditable]"
