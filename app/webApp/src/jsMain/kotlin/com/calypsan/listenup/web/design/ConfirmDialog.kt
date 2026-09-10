package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable

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
    ModalDialog(open = open, title = title, onDismiss = onDismiss) {
        DialogText(body)
        DialogActions(confirmLabel = confirmLabel, onConfirm = onConfirm, onDismiss = onDismiss)
    }
}
