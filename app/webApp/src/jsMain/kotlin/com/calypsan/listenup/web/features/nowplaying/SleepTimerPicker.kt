package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.web.design.Dialog
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.events.Event

/**
 * Stop listening at a time you choose, without having to be awake for it.
 *
 * Three states, because a sleep timer is three different questions. Nothing running asks *when*;
 * something running asks *how much longer, and do you want more*; and a fade in progress asks
 * nothing at all — it is already ending, and offering controls over a decision that has been made
 * would invite a tap that arrives too late to mean anything.
 *
 * The ladders are `SleepTimerSheet`'s, not new ones: the same 15/30/45/60/120 to start and the same
 * 5/10/15 to extend, so a listener who set a timer on their phone last night finds the same choices
 * in the browser this morning. `SleepTimerMode.Duration.label` renders them, so "120" reads as
 * "2 hours" here exactly as it does there.
 *
 * Built on the real `<dialog>` with `showModal()`, matching [ChapterPicker] rather than inventing a
 * second modal shape — that is what buys the focus trap, the inert page behind, and
 * Escape-to-close for free.
 */
@Composable
internal fun SleepTimerPicker(
    open: Boolean,
    state: SleepTimerState,
    hasChapters: Boolean,
    onSet: (SleepTimerMode) -> Unit,
    onCancel: () -> Unit,
    onExtend: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) return

    Dialog(attrs = {
        classes("dlg", "sleep-dlg")
        attr("aria-labelledby", SLEEP_TITLE_ID)
        ref { element ->
            val dialog = element as HTMLDialogElement
            if (!dialog.open) dialog.showModal()
            // Escape and the backdrop fire `close` without touching any button here, so the caller
            // has to hear about it or its `open` flag drifts out of step with the DOM.
            val onClose: (Event) -> Unit = { onDismiss() }
            dialog.addEventListener("close", onClose)
            onDispose { dialog.removeEventListener("close", onClose) }
        }
    }) {
        H2(attrs = {
            classes("dlg-t")
            attr("id", SLEEP_TITLE_ID)
        }) { Text("Sleep timer") }

        when (state) {
            is SleepTimerState.Inactive -> IdleOptions(hasChapters = hasChapters, onSet = onSet)
            is SleepTimerState.Active -> RunningTimer(state = state, onExtend = onExtend, onCancel = onCancel)
            is SleepTimerState.FadingOut -> P(attrs = { classes("sleep-fade") }) { Text("Fading out…") }
        }

        Button(attrs = {
            classes("btn-ghost")
            attr("type", VALUE_BUTTON)
            onClick { onDismiss() }
        }) {
            Icon(WebIcon.X, size = CLOSE_ICON_SIZE)
            Text("Close")
        }
    }
}

/** Nothing is running: pick how long. */
@Composable
private fun IdleOptions(
    hasChapters: Boolean,
    onSet: (SleepTimerMode) -> Unit,
) {
    Div(attrs = { classes("sleep-opts") }) {
        DURATION_OPTIONS.forEach { minutes ->
            val mode = SleepTimerMode.Duration(minutes)
            Button(attrs = {
                classes("sleep-opt")
                attr("type", VALUE_BUTTON)
                onClick { onSet(mode) }
            }) {
                Text(mode.label)
            }
        }
    }

    // Only when the book has marks. End-of-chapter waits to be told a chapter turned over, and a
    // book with no chapters never will — the timer would sit Active forever, which is a control
    // that quietly does nothing rather than one that was never offered.
    if (hasChapters) {
        Button(attrs = {
            classes("sleep-eoc")
            attr("type", VALUE_BUTTON)
            onClick { onSet(SleepTimerMode.EndOfChapter) }
        }) {
            Span(attrs = { classes("sleep-eoc-t") }) { Text("End of chapter") }
            Span(attrs = { classes("sleep-eoc-s") }) { Text("Stops when this chapter finishes") }
        }
    }
}

/** A timer is running: say how much is left, and offer more of it. */
@Composable
private fun RunningTimer(
    state: SleepTimerState.Active,
    onExtend: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val isDuration = state.mode is SleepTimerMode.Duration

    // `role="timer"` with `aria-live="off"`: the countdown re-renders every second, and a live
    // region here would have a screen reader interrupt the book to read a new number 900 times.
    // The role still lets someone ask for it; nothing announces it at them.
    P(attrs = {
        classes("mono", "sleep-left")
        attr("role", "timer")
        attr("aria-live", "off")
    }) {
        Text(if (isDuration) state.formatRemaining() else "End of chapter")
    }

    // Extending is arithmetic on a countdown, so it is offered only where there is one. An
    // end-of-chapter timer ends when the chapter does; "+5 min" against it would be a button
    // whose press changes nothing.
    if (isDuration) {
        Div(attrs = { classes("sleep-opts") }) {
            EXTEND_OPTIONS.forEach { minutes ->
                Button(attrs = {
                    classes("sleep-opt")
                    attr("type", VALUE_BUTTON)
                    onClick { onExtend(minutes) }
                }) {
                    Text("+$minutes min")
                }
            }
        }
    }

    Button(attrs = {
        classes("btn")
        attr("type", VALUE_BUTTON)
        onClick { onCancel() }
    }) {
        Text("Cancel timer")
    }
}

/** The ladder `SleepTimerSheet` offers, so both clients start the same timers. */
private val DURATION_OPTIONS = listOf(15, 30, 45, 60, 120)

/** The extend ladder, likewise. */
private val EXTEND_OPTIONS = listOf(5, 10, 15)

/** Named once: four buttons in this file set the same attribute. */
private const val VALUE_BUTTON = "button"

private const val SLEEP_TITLE_ID = "sleep-timer-title"

private const val CLOSE_ICON_SIZE = 17
