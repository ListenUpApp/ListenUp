package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.events.Event

/**
 * Asks before something irreversible happens.
 *
 * Built on the real `<dialog>` element and opened with `showModal()`, which is what buys the three
 * behaviours a hand-rolled modal has to reimplement: focus is trapped inside it, the page behind
 * goes inert, and Escape closes it. A `div` with `role="dialog"` looks the same and does none of
 * that unless someone writes it — and the someone is always in a hurry.
 *
 * ## Why this exists
 *
 * Deleting a shelf shipped as a two-step inline confirm because web had no dialog, and the note
 * said the pattern should be decided before a second destructive action needed it. "Sign out
 * everywhere" is the second. This is that decision, and Delete Shelf now uses it too rather than
 * leaving two shapes of "are you sure" in one app.
 *
 * ## No red
 *
 * The sheet has no danger token and coral is already the "this is the action" colour, so borrowing
 * it for a destructive confirm would say nothing extra. The safety here is structural instead: the
 * dialog states the consequence in words, and Cancel takes focus. That is the same resolution Delete
 * Shelf reached before this existed, kept rather than quietly replaced with a colour.
 *
 * @param confirmLabel The verb, not "OK" — someone reading only the buttons should still know what
 *   is about to happen.
 */
@Composable
fun ConfirmDialog(
    open: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!open) return

    Dialog(attrs = {
        classes("dlg")
        attr("aria-labelledby", TITLE_ID)
        ref { element ->
            val dialog = element as HTMLDialogElement
            // showModal(), not the `open` attribute: only the method gives the top layer, the
            // inert background and the focus trap. Setting `open` renders it non-modally.
            if (!dialog.open) dialog.showModal()
            // Escape and the backdrop both fire `cancel`/`close` without touching our buttons, so
            // the caller has to hear about it or its `open` flag drifts out of step with reality.
            val onClose: (Event) -> Unit = { onDismiss() }
            dialog.addEventListener("close", onClose)
            onDispose {
                dialog.removeEventListener("close", onClose)
                if (dialog.open) dialog.close()
            }
        }
    }) {
        Div(attrs = { classes("dlg-body") }) {
            H2(attrs = {
                classes("dlg-t")
                attr("id", TITLE_ID)
            }) { Text(title) }
            P(attrs = { classes("dlg-p") }) { Text(body) }
            Div(attrs = { classes("dlg-actions") }) {
                // Cancel first in the DOM so it takes initial focus: the safe choice should be the
                // one a hurried Return keypress lands on.
                Button(attrs = {
                    classes("btn-o")
                    attr("type", "button")
                    onClick { onDismiss() }
                }) { Text("Cancel") }
                Button(attrs = {
                    classes("btn")
                    attr("type", "button")
                    onClick { onConfirm() }
                }) { Text(confirmLabel) }
            }
        }
    }
}

private const val TITLE_ID = "lu-dialog-title"
