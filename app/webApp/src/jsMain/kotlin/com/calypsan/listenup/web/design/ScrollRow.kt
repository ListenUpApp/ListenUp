package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.web.dom.ResizeObserver
import com.calypsan.listenup.web.motion.prefersLessMotion
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.AUTO
import org.w3c.dom.HTMLElement
import org.w3c.dom.SMOOTH
import org.w3c.dom.ScrollBehavior
import org.w3c.dom.ScrollToOptions
import org.w3c.dom.asList
import org.w3c.dom.events.Event

/** Where a [ScrollRow] has been scrolled to, which decides which of its arrows can move it. */
enum class ScrollRowPosition {
    /** Every item is in view: there is nowhere to scroll, so there are no arrows. */
    Fits,

    /** At the first item, with more past the trailing edge. */
    Start,

    /** Part-way: items past both edges. */
    Middle,

    /** At the last item, with more past the leading edge. */
    End,
    ;

    /** Whether there is anything back towards the first item. */
    val canGoBack: Boolean get() = this == Middle || this == End

    /** Whether there is anything on towards the last item. */
    val canGoForward: Boolean get() = this == Start || this == Middle
}

/**
 * A [ScrollRow]'s track and where it is, shared with its [ScrollRowArrows] wherever those sit (a section's
 * header, beside its other actions). Made with [rememberScrollRowState].
 */
@Stable
class ScrollRowState internal constructor() {
    /** Where the row is now; [ScrollRowPosition.Fits] until a track is on the page. */
    var position by mutableStateOf(ScrollRowPosition.Fits)
        private set

    private var track: HTMLElement? = null

    internal fun attach(element: HTMLElement) {
        track = element
        measure()
    }

    internal fun detach() {
        track = null
        position = ScrollRowPosition.Fits
    }

    internal fun measure() {
        val row = track ?: return
        position = scrollRowPosition(row.scrollLeft, row.clientWidth.toDouble(), row.scrollWidth.toDouble())
    }

    /**
     * Moves the row a page forward (the item peeking at the trailing edge comes to the leading edge) or back
     * (the first item in full view goes to the trailing edge). It glides unless the reader asked for less
     * motion, when it jumps.
     */
    internal fun page(forward: Boolean) {
        val row = track ?: return
        val box = row.getBoundingClientRect()
        val style = window.getComputedStyle(row)
        val start = box.left + (style.paddingLeft.removeSuffix("px").toDoubleOrNull() ?: 0.0)
        val end = box.right - (style.paddingRight.removeSuffix("px").toDoubleOrNull() ?: 0.0)
        val items = row.children.asList().map { it.getBoundingClientRect() }
        val delta =
            if (forward) {
                items.firstOrNull { it.right > end + EDGE_SLACK_PX }?.let { it.left - start }
                    ?: row.clientWidth.toDouble()
            } else {
                items.firstOrNull { it.left >= start - EDGE_SLACK_PX }?.let { it.right - end }
                    ?: -row.clientWidth.toDouble()
            }
        row.scrollTo(
            ScrollToOptions(
                left = row.scrollLeft + delta,
                behavior = if (prefersLessMotion()) ScrollBehavior.AUTO else ScrollBehavior.SMOOTH,
            ),
        )
    }
}

/** A [ScrollRowState] that lives as long as the composition that remembers it. */
@Composable
fun rememberScrollRowState(): ScrollRowState = remember { ScrollRowState() }

/**
 * Where a row scrolled to [scrollLeft] of [scrollWidth], [clientWidth] wide, is. A pixel either way is
 * slack: a fractional layout never quite reaches its ends.
 */
internal fun scrollRowPosition(
    scrollLeft: Double,
    clientWidth: Double,
    scrollWidth: Double,
): ScrollRowPosition =
    when {
        scrollWidth <= clientWidth + EDGE_SLACK_PX -> ScrollRowPosition.Fits
        scrollLeft <= EDGE_SLACK_PX -> ScrollRowPosition.Start
        scrollLeft + clientWidth >= scrollWidth - EDGE_SLACK_PX -> ScrollRowPosition.End
        else -> ScrollRowPosition.Middle
    }

/**
 * One row of items that never wraps: it scrolls sideways (a swipe, a trackpad, Shift and the wheel, or
 * Tab onto an item past the edge) and comes to rest on an item's leading edge. The item past the edge
 * peeks, which is what says there is more.
 *
 * [classes] names the row for its page (item widths, the gap); [state] lets [ScrollRowArrows] move it.
 */
@Composable
fun ScrollRow(
    state: ScrollRowState,
    classes: List<String>,
    content: @Composable () -> Unit,
) {
    Div(attrs = {
        classes("scroll-row")
        classes(classes)
        ref { element ->
            val onScroll: (Event) -> Unit = { state.measure() }
            element.addEventListener("scroll", onScroll)
            val resizes = ResizeObserver { _, _ -> state.measure() }
            resizes.observe(element)
            state.attach(element)
            onDispose {
                element.removeEventListener("scroll", onScroll)
                resizes.disconnect()
                state.detach()
            }
        }
    }) { content() }
}

/**
 * The back and forward arrows for a [ScrollRow], for a pointer that cannot swipe; on a touchscreen they step
 * aside (`05-patterns.css`). Each is named for where it goes, and is unavailable at its end of the row; an
 * arrow that has just reached the end keeps the keyboard focus it was pressed with. With nothing to scroll
 * there are none.
 */
@Composable
fun ScrollRowArrows(
    state: ScrollRowState,
    backLabel: String,
    forwardLabel: String,
) {
    val position = state.position
    if (position == ScrollRowPosition.Fits) return
    Div(attrs = { classes("scroll-row-arrows") }) {
        Button(
            kind = ButtonKind.Icon,
            size = ButtonSize.Sm,
            label = backLabel,
            pressable = position.canGoBack,
            onClick = { state.page(forward = false) },
            attrs = { classes("scroll-row-arrow") },
        ) { Icon(WebIcon.ChevronLeft, size = ARROW_ICON) }
        Button(
            kind = ButtonKind.Icon,
            size = ButtonSize.Sm,
            label = forwardLabel,
            pressable = position.canGoForward,
            onClick = { state.page(forward = true) },
            attrs = { classes("scroll-row-arrow") },
        ) { Icon(WebIcon.ChevronRight, size = ARROW_ICON) }
    }
}

/** The arrows' glyph, in px. */
private const val ARROW_ICON = 16

/** How near an end, in px, counts as at it. */
private const val EDGE_SLACK_PX = 1.0
