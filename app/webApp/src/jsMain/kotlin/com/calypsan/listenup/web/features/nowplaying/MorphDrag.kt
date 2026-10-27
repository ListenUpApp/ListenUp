package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.client.presentation.player.PlayerMorph
import com.calypsan.listenup.client.presentation.player.progressOf
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.pointerevents.PointerEvent
import kotlin.math.abs

/**
 * A finger on the morph (canvas `GestureWeb`, DRAG and DRAG DOWN TO CLOSE): up from the bar's book, or down from Now
 * Playing, below 760px, with any pointer. Under [PlayerMorph.TOUCH_SLOP_DP] a press is still a click; past it the drag
 * owns the gesture, the sheet's top edge follows the finger 1:1, and the click that follows is swallowed so a link
 * never opens a second time. On release [MorphController.release] applies the shared rule.
 *
 * Every move writes one number ([MorphController.dragTo]) and nothing else: no state, no recomposition. Screen readers
 * never reach any of this; their way in and out is the link and Collapse.
 *
 * The close drag starts anywhere on Now Playing except the scrubber (it keeps its own drag), and only with the page
 * scrolled to its top. On touch only the bar's book, the header and the cover opt out of the browser's own panning
 * (`touch-action`, `06-shell.css` and `05-patterns.css`), so the rest of the page still scrolls first.
 */
internal class MorphDrag(
    private val morph: MorphController,
) {
    private var drag: Drag? = null

    /** Clicks before this moment (ms, `performance.now()`) are the tail of a drag, not a press. */
    private var swallowClicksUntil = 0.0

    /** Listens on [shell] for presses and on its window for the moves that follow; returns the call that stops. */
    fun install(shell: HTMLElement): () -> Unit {
        val view = shell.ownerDocument?.defaultView ?: window
        val onDown: (Event) -> Unit = { event -> (event as? PointerEvent)?.let { onPointerDown(it, shell) } }
        val onMove: (Event) -> Unit = { event -> (event as? PointerEvent)?.let(::onPointerMove) }
        val onUp: (Event) -> Unit = { event -> (event as? PointerEvent)?.let { onPointerUp(it, released = true) } }
        val onCancel: (Event) -> Unit = { event -> (event as? PointerEvent)?.let { onPointerUp(it, released = false) } }
        val onClick: (Event) -> Unit = { event ->
            if (view.performance.now() < swallowClicksUntil) {
                swallowClicksUntil = 0.0
                event.preventDefault()
                event.stopPropagation()
            }
        }
        shell.addEventListener("pointerdown", onDown)
        view.addEventListener("pointermove", onMove)
        view.addEventListener("pointerup", onUp)
        view.addEventListener("pointercancel", onCancel)
        // Capturing, on the shell: it sees the click before the link it would open does.
        shell.addEventListener("click", onClick, true)
        return {
            shell.removeEventListener("pointerdown", onDown)
            view.removeEventListener("pointermove", onMove)
            view.removeEventListener("pointerup", onUp)
            view.removeEventListener("pointercancel", onCancel)
            shell.removeEventListener("click", onClick, true)
            drag = null
        }
    }

    private fun onPointerDown(
        event: PointerEvent,
        shell: HTMLElement,
    ) {
        if (drag != null || event.button.toInt() != 0 || !morph.acceptsDrag()) return
        val target = event.target as? Element ?: return
        val opens = !morph.isOnPlaying()
        val origin =
            if (opens) {
                target.closest(".tport-body") as? HTMLElement
            } else {
                (target.closest(".np-morph") as? HTMLElement)?.takeIf { isFreeToClose(target, shell) }
            } ?: return
        swallowClicksUntil = 0.0
        drag = Drag(opens = opens, pointerId = event.pointerId, startY = event.clientY.toDouble(), origin = origin)
        drag?.sample(event)
        // Capture where the browser allows it, so the moves follow the finger off the element; a synthetic pointer
        // has nothing to capture, and its moves bubble to the window regardless.
        runCatching { target.asDynamic().setPointerCapture(event.pointerId) }
    }

    private fun onPointerMove(event: PointerEvent) {
        val current = drag?.takeIf { it.pointerId == event.pointerId } ?: return
        current.sample(event)
        val dy = event.clientY - current.startY
        if (!current.isDragging) {
            if (abs(dy) < PlayerMorph.TOUCH_SLOP_DP) return
            // Down on the bar, or up on Now Playing, is not this gesture: let the press be whatever else it is.
            val wrongWay = if (current.opens) dy > 0 else dy < 0
            val started =
                !wrongWay && if (current.opens) morph.beginDragOpen(current.origin) else morph.beginDragClose()
            if (!started) {
                drag = null
                return
            }
            current.isDragging = true
        }
        val travel = morph.travel.toFloat()
        val sheetTop = if (current.opens) travel + dy.toFloat() else dy.toFloat()
        morph.dragTo(progressOf(sheetTopPx = sheetTop, barTopPx = travel))
    }

    private fun onPointerUp(
        event: PointerEvent,
        released: Boolean,
    ) {
        val current = drag?.takeIf { it.pointerId == event.pointerId } ?: return
        drag = null
        if (!current.isDragging) return
        val view = current.origin.ownerDocument?.defaultView ?: window
        swallowClicksUntil = view.performance.now() + CLICK_AFTER_DRAG_MS
        // A cancelled pointer (the browser took it) carries no speed of its own: the position decides.
        morph.release(if (released) current.velocityPxPerSec() else 0f)
    }

    /** A press on Now Playing that may close it: not on the scrubber or in a field or menu, and the page at its top. */
    private fun isFreeToClose(
        target: Element,
        shell: HTMLElement,
    ): Boolean {
        val keepsItsOwnDrag = target.closest(".np-scrub, input, textarea, select, dialog, .menu") != null
        val scrolled = ((shell.querySelector(".shell-main") as? HTMLElement)?.scrollTop ?: 0.0) > 0.0
        return !keepsItsOwnDrag && !scrolled
    }

    /** One finger's gesture: where it started, and where it has been over the last [VELOCITY_WINDOW_MS]. */
    private class Drag(
        val opens: Boolean,
        val pointerId: Int,
        val startY: Double,
        val origin: HTMLElement,
    ) {
        var isDragging = false
        private val samples = ArrayDeque<Pair<Double, Double>>()

        fun sample(event: PointerEvent) {
            val now = event.timeStamp.toDouble()
            samples.addLast(now to event.clientY.toDouble())
            while (samples.size > 2 && now - samples.first().first > VELOCITY_WINDOW_MS) samples.removeFirst()
        }

        /** px per second, positive downward, as pointer coordinates are. */
        fun velocityPxPerSec(): Float {
            val first = samples.firstOrNull() ?: return 0f
            val last = samples.last()
            val elapsed = last.first - first.first
            return if (elapsed <= 0.0) 0f else ((last.second - first.second) / elapsed * MILLIS_PER_SECOND).toFloat()
        }
    }

    private companion object {
        /** The last tenth of a second of movement is the release's speed. */
        const val VELOCITY_WINDOW_MS = 100.0

        const val MILLIS_PER_SECOND = 1000.0

        /** Long enough for the browser's click after a pointerup, short enough never to eat a real tap. */
        const val CLICK_AFTER_DRAG_MS = 400.0
    }
}
