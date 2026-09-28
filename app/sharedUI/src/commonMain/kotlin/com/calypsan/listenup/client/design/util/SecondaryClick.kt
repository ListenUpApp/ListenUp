package com.calypsan.listenup.client.design.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Answers a secondary (right) mouse click with [onSecondaryClick] — the pointer's way to reach what a
 * long press reaches on a touchscreen, such as a context menu or selection.
 *
 * Place it before the element's `clickable`: it sees the press first and consumes the whole
 * right-click, so the primary click never fires for it. Touch, stylus and primary-button presses
 * pass through untouched. A null [onSecondaryClick] leaves the modifier chain as it was.
 */
fun Modifier.onSecondaryClick(onSecondaryClick: (() -> Unit)?): Modifier =
    if (onSecondaryClick == null) {
        this
    } else {
        pointerInput(onSecondaryClick) {
            awaitEachGesture {
                val press = awaitPointerEvent(PointerEventPass.Initial)
                if (press.type == PointerEventType.Press && press.buttons.isSecondaryPressed) {
                    press.changes.forEach { it.consume() }
                    // Swallow the rest of the gesture so the click never sees its release.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    onSecondaryClick()
                }
            }
        }
    }
