package com.calypsan.listenup.web

import kotlinx.coroutines.delay
import org.w3c.dom.HTMLElement
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.pointerevents.PointerEventInit

/**
 * A finger, pen or mouse pressed on [target] and moved by hand: `pointerdown` there, `pointermove`s, `pointerup`.
 *
 * Every event is dispatched on [target], as pointer capture would deliver it, and bubbles to its window. A synthetic
 * event's `timeStamp` is the moment it is made, so the time between moves is real: [moveBy] waits [stepMs] after
 * each step, and a velocity read from the events is the velocity the spec drew.
 */
internal class PointerDrag(
    private val target: HTMLElement,
    private val pointerType: String,
    private val x: Double,
    private var y: Double,
) {
    fun down() = dispatch("pointerdown", buttons = 1)

    /** Moves [dy] px (negative is up) in [steps] equal steps, [stepMs] apart. */
    suspend fun moveBy(
        dy: Double,
        steps: Int = 1,
        stepMs: Long = STEP_MS,
    ) {
        repeat(steps) {
            y += dy / steps
            dispatch("pointermove", buttons = 1)
            delay(stepMs)
        }
    }

    fun up() = dispatch("pointerup", buttons = 0)

    private fun dispatch(
        type: String,
        buttons: Int,
    ) {
        val init = js("{}")
        init.pointerId = POINTER_ID
        init.pointerType = pointerType
        init.isPrimary = true
        init.clientX = x
        init.clientY = y
        init.button = 0
        init.buttons = buttons
        init.bubbles = true
        init.cancelable = true
        target.dispatchEvent(PointerEvent(type, init.unsafeCast<PointerEventInit>()))
    }

    private companion object {
        const val POINTER_ID = 7

        /** One frame at 60 Hz. */
        const val STEP_MS = 16L
    }
}

/** A [pointerType] press at the middle of this element. */
internal fun HTMLElement.press(pointerType: String = "touch"): PointerDrag {
    val box = getBoundingClientRect()
    return PointerDrag(this, pointerType, box.left + box.width / 2, box.top + box.height / 2).also { it.down() }
}
