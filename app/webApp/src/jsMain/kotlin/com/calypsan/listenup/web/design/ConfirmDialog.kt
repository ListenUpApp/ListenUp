package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * Asks before something irreversible happens.
 *
 * Built on [ModalDialog], which owns the real `<dialog>` and its `showModal()` — the focus trap,
 * the inert background and Escape-to-close all come from there. What this adds is the shape of a
 * yes-or-no question: a sentence, a Cancel, and a verb.
 *
 * ## Why this exists
 *
 * Deleting a shelf shipped as a two-step inline confirm because web had no dialog, and the note
 * said the pattern should be decided before a second destructive action needed it. "Sign out all
 * other devices" is the second. This is that decision, and Delete Shelf now uses it too rather than
 * leaving two shapes of "are you sure" in one app.
 *
 * ## No red
 *
 * The sheet has no danger token and coral is already the "this is the action" colour, so borrowing
 * it for a destructive confirm would say nothing extra. The safety here is structural instead: the
 * dialog states the consequence in words, and Cancel takes focus. A destructive confirm
 * (`destructive = true`) is the one exception: deleting a book's folder from the server.
 *
 * @param confirmLabel The verb, not "OK" — someone reading only the buttons should still know what
 *   is about to happen.
 * @param error Why the last attempt was refused, rendered inside the dialog and announced. A caller
 *   that keeps the dialog open on failure needs this: the refusal is only useful where the decision
 *   is being made, and a toast over a dialog that still says "are you sure?" reads as success.
 * @param confirmEnabled False while the confirmed work is in flight, so a second press cannot race
 *   the first.
 * @param acknowledgement A sentence the reader must tick before [confirmLabel] works; cleared every time the
 *   dialog opens. Null draws no checkbox.
 * @param destructive The confirm wears the danger colour ([ButtonKind.Danger]).
 */
@Composable
fun ConfirmDialog(
    open: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
    confirmEnabled: Boolean = true,
    acknowledgement: String? = null,
    destructive: Boolean = false,
) {
    var acknowledged by remember(open) { mutableStateOf(false) }
    ModalDialog(open = open, title = title, onDismiss = onDismiss) {
        DialogText(body)
        acknowledgement?.let { sentence ->
            CheckboxField(label = sentence, checked = acknowledged, onChange = { acknowledged = it })
        }
        error?.let { message ->
            P(attrs = {
                classes("dlg-err")
                attr("role", "alert")
            }) { Text(message) }
        }
        DialogActions(
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            confirmEnabled = confirmEnabled && (acknowledgement == null || acknowledged),
            confirmKind = if (destructive) ButtonKind.Danger else ButtonKind.Primary,
        )
    }
}
