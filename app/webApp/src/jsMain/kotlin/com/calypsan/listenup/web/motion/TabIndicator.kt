package com.calypsan.listenup.web.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Span
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * The single underline of a `Tabs` strip, which slides to the tab the reader picks over
 * [MotionToken.MOVE] instead of one underline vanishing and another appearing.
 *
 * Rendered as the strip's last child, positioned against the strip from the active tab's
 * `offsetLeft`/`offsetWidth` — offsets, not client rects, so the page body's arrival rise (a
 * transform) cannot skew it. Its box is Compose-managed style; the slide itself is a FLIP transform
 * through [animateComposited], so it is composited and honours reduced motion.
 *
 * It slides only when the ACTIVE TAB changes. Its first placement, a resize, or a count that widens
 * a tab ("Chapters 44" → "Chapters 1,244") move it without a slide — that is layout, not the reader.
 *
 * Non-restartable so it re-measures on every recomposition of its strip: a tab's label or count can
 * change without any of this function's own arguments changing.
 */
@Composable
@NonRestartableComposable
internal fun TabIndicator(active: String) {
    val ink = remember { Ink() }
    var box by remember { mutableStateOf<InkBox?>(null) }
    val measureNow: () -> Unit = {
        val next = ink.element?.let(::measureActiveTab)
        if (next != null) {
            if (next != box) {
                // Slide from where it was only if the reader moved it; a first placement or a layout
                // change just puts it there.
                ink.slideFrom = box.takeIf { ink.lastActive != null && ink.lastActive != active }
                box = next
            }
            ink.lastActive = active
        }
    }
    val measure = rememberUpdatedState(measureNow)

    Span(attrs = {
        classes("tab-ink")
        attr("aria-hidden", "true")
        box?.let { placed ->
            classes("is-placed")
            style {
                property("left", "${placed.left}px")
                property("top", "${placed.top}px")
                property("width", "${placed.width}px")
                property("height", "${placed.height}px")
            }
        }
        ref { element ->
            ink.element = element
            onDispose { ink.element = null }
        }
    })

    SideEffect { measure.value() }
    DisposableEffect(box) {
        val from = ink.slideFrom
        val to = box
        val element = ink.element
        ink.slideFrom = null
        if (from != null && to != null && element != null) slide(element, from, to)
        onDispose { }
    }
    DisposableEffect(Unit) {
        val onResize: (Event) -> Unit = { measure.value() }
        window.addEventListener("resize", onResize)
        onDispose { window.removeEventListener("resize", onResize) }
    }
}

/** Where the ink stands, relative to its strip. */
private data class InkBox(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

/** Plain holder, not state: reading these must not recompose anything. */
private class Ink {
    var element: HTMLElement? = null
    var lastActive: String? = null
    var slideFrom: InkBox? = null
}

/** The active tab's bottom edge, as a box relative to the strip; null until the strip is laid out. */
private fun measureActiveTab(ink: HTMLElement): InkBox? {
    val tab = ink.parentElement?.querySelector(ACTIVE_TAB) as? HTMLElement ?: return null
    if (tab.offsetWidth <= 0) return null
    return InkBox(
        left = tab.offsetLeft.toDouble(),
        top = (tab.offsetTop + tab.offsetHeight - INK_HEIGHT_PX).toDouble(),
        width = tab.offsetWidth.toDouble(),
        height = INK_HEIGHT_PX.toDouble(),
    )
}

/** FLIP: from the old box to the new one, as a transform from the new box's top-left. */
private fun slide(
    element: Element,
    from: InkBox,
    to: InkBox,
) {
    val dx = from.left - to.left
    val scale = from.width / to.width
    animateComposited(
        element,
        listOf(Keyframe.transform("translateX(${dx}px) scaleX($scale)"), Keyframe.transform("none")),
        MotionToken.MOVE,
        transformOrigin = "0 0",
    )
}

private const val ACTIVE_TAB = "[role=tab][aria-selected=true]"

/** The underline's weight — the 2px `border-bottom` `.tab.on` draws without the ink. */
private const val INK_HEIGHT_PX = 2
