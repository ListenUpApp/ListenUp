package com.calypsan.listenup.web.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.dom.Div
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * The page's own content box inside the shell's region, and how a new page arrives in it: the
 * region fades, and the content rises [SETTLE_RISE_PX] into place.
 *
 * ⛔ **The region (`.shell-main`) never takes a transform — opacity only.** The hero flight measures
 * its destination with `getBoundingClientRect()`, and a transform on an ancestor offsets every
 * descendant's rect. That is the whole reason this box exists: the rise needs an element that is
 * *inside* the region and holds the page, so the region itself can stay still.
 *
 * ⛔ **The rise starts before any flight measures, on purpose.** It begins synchronously here, in the
 * same apply that mounts the page; a flight measures a frame later from inside a
 * `requestAnimationFrame`. Its rect then already includes the rise's offset, so the element starts
 * exactly at its origin, and — the rise being as long as a flight and started first — ends exactly
 * in its box. Starting the rise after the measurement would put the element 8px off its origin on
 * the flight's first frame.
 *
 * Only a change of PAGE moves anything ([isPageChange]); the first paint does not.
 */
@Composable
internal fun PageArrival(
    page: String,
    content: @Composable () -> Unit,
) {
    val body = remember { PageBody() }
    Div(attrs = {
        classes("page-body")
        ref { element ->
            body.element = element
            onDispose { body.element = null }
        }
    }) { content() }
    DisposableEffect(page) {
        val previous = body.page
        body.page = page
        val element = body.element
        if (element != null && isPageChange(previous, page)) {
            element.parentElement?.let(::fadePageIn)
            settleContentIn(element)
        }
        onDispose { }
    }
}

/** Fades the content region in. Opacity only, never a transform — see [PageArrival]. */
internal fun fadePageIn(region: Element) {
    animateComposited(region, listOf(Keyframe.opacity(0.0), Keyframe.opacity(1.0)), MotionToken.QUICK)
}

/** Rises the page's content box into place from [SETTLE_RISE_PX] below. */
internal fun settleContentIn(body: Element) {
    animateComposited(
        body,
        listOf(Keyframe.transform("translateY(${SETTLE_RISE_PX}px)"), Keyframe.transform("none")),
        MotionToken.MOVE,
    )
}

/**
 * Whether this is a change of PAGE, not of route. `/library?sort=title` → `/library?sort=added` is
 * the same page rearranging itself, and moving the whole page for it would punish the reader for
 * using a control. `from` null is the first paint, which arrives without ceremony.
 */
internal fun isPageChange(
    from: String?,
    to: String,
): Boolean = from != null && from != to

/** Plain holder, not state: what the box is and which page it last showed must not recompose anything. */
private class PageBody {
    var element: HTMLElement? = null
    var page: String? = null
}

/** Enough to read as settling, small enough never to read as the page moving. */
private const val SETTLE_RISE_PX = 8
