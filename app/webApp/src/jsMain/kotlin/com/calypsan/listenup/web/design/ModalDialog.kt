package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.events.Event

/**
 * The modal shell every dialog in the app is built on: a real `<dialog>` opened with `showModal()`.
 *
 * That method, not the `open` attribute, is what buys the three behaviours a hand-rolled modal has
 * to reimplement — focus is trapped inside it, the page behind goes inert, and Escape closes it. A
 * `div` with `role="dialog"` looks the same and does none of that unless someone writes it, and the
 * someone is always in a hurry.
 *
 * Lifted out of [ConfirmDialog] when Categories needed four dialogs that ask for a *value* rather
 * than a yes or no. Two copies of the `showModal`/`close`-listener/`onDispose` dance is two places
 * for the focus trap or the Escape wiring to be subtly wrong, and the wrong one is the one nobody
 * tests.
 *
 * The caller supplies the whole body below the title, including its own actions row — that is the
 * only difference between a confirm and a form, and it is not worth a second shell.
 */
@Composable
fun ModalDialog(
    open: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!open) return

    Dialog(attrs = {
        classes("dlg")
        attr("aria-labelledby", DIALOG_TITLE_ID)
        ref { element ->
            val dialog = element as HTMLDialogElement
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
                attr("id", DIALOG_TITLE_ID)
            }) { Text(title) }
            content()
        }
    }
}

/**
 * A dialog's Cancel and its verb.
 *
 * Cancel is first in the DOM so it takes initial focus: the safe choice should be the one a hurried
 * Return keypress lands on. [confirmLabel] is the verb rather than "OK" — someone reading only the
 * buttons should still know what is about to happen.
 *
 * [confirmEnabled] false renders a genuinely `disabled` button. The alternative is accepting the
 * press and letting the server refuse, which arrives as a toast over a dialog still holding the
 * same unusable input.
 */
@Composable
fun DialogActions(
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    Div(attrs = { classes("dlg-actions") }) {
        Button(attrs = {
            classes("btn-o")
            attr("type", BUTTON_TYPE)
            onClick { onDismiss() }
        }) { Text("Cancel") }
        Button(attrs = {
            classes("btn")
            attr("type", BUTTON_TYPE)
            if (!confirmEnabled) attr("disabled", "")
            onClick { if (confirmEnabled) onConfirm() }
        }) { Text(confirmLabel) }
    }
}

/** A paragraph inside a dialog: what the thing about to happen actually does. */
@Composable
fun DialogText(text: String) {
    P(attrs = { classes("dlg-p") }) { Text(text) }
}

private const val BUTTON_TYPE = "button"

/**
 * Shared by every dialog, because only one is ever open: `showModal()` makes the rest of the page
 * inert, so a second dialog cannot be reached to open it.
 */
internal const val DIALOG_TITLE_ID = "lu-dialog-title"
