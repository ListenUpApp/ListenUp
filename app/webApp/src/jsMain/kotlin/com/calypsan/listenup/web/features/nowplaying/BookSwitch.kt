package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.web.motion.Keyframe
import com.calypsan.listenup.web.motion.MotionToken
import com.calypsan.listenup.web.motion.animateComposited
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * How the player bar meets a new book (Simon, 2026-10-14): the book — cover, title and line — rises
 * [ARRIVAL_RISE_PX] into place as it fades in, while the bar and its controls stay exactly where they are. The
 * switch reads as deliberate rather than as a one-frame flicker, and the main button never moves under the cursor.
 *
 * ⛔ Keyed on the book's id, never on the state: a pause, a stall or a position tick is the same book and moves
 * nothing. The first book a bar shows arrives without ceremony, as a page's first paint does (PageArrival.kt).
 * ⛔ The bar itself never leaves: the arrival animates the body inside it, so there is no frame without a bar
 * (and `PlayerViewModel` holds its last frame through the switch, so the host never draws nothing).
 * Reduced motion is [animateComposited]'s to honour: the new book then simply appears. While the bar is growing into Now
 * Playing (`.shell-morphing`) the switch moves nothing: the morph is already moving it.
 *
 * Plain holder, not state: which book the bar last showed must not recompose anything.
 */
internal class BookSwitch {
    /** The bar's body, while it is mounted. */
    var body: HTMLElement? = null

    private var bookId: String? = null

    /** Records that the bar now shows [id], and brings the body in if that is a different book. */
    fun show(id: String) {
        val previous = bookId
        bookId = id
        val element = body
        // Mid-morph the bar is the morph's to move: a second motion on its book would restart under the finger.
        val isMorphing = element?.closest(".shell-morphing") != null
        if (previous != null && previous != id && element != null && !isMorphing) settleBookIn(element)
    }
}

/** Fades [body] in as it rises into place. Opacity and transform only, so nothing lays out again. */
internal fun settleBookIn(body: Element) {
    animateComposited(
        body,
        listOf(
            Keyframe.of(opacity = 0.0, transform = "translateY(${ARRIVAL_RISE_PX}px)"),
            Keyframe.of(opacity = 1.0, transform = "none"),
        ),
        MotionToken.MOVE,
    )
}

/** Enough to read as arriving, small enough to stay inside the bar. */
private const val ARRIVAL_RISE_PX = 8
