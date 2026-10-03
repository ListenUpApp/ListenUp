package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Text
import kotlinx.browser.window
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
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
 *
 * [panelClass] sits beside `dlg` for a dialog with its own width or layout — the player panels, the
 * command palette. It also names the heading's id, so a panel opened from inside another (a picker
 * over Now Playing) cannot collide with the one beneath it.
 *
 * [showTitle] false is for a surface whose field says what it is (the command palette): the title
 * then names the dialog through `aria-label` instead of a heading nobody sees.
 *
 * Closing hands focus back to whatever opened it — Cancel, Escape and the confirming verb alike. The
 * dialog is torn down rather than closed in place, so the browser's own restore never runs, and focus
 * fell to `<body>`: the reader's next Tab started again from the top of the page. When the opener has
 * gone (the verb removed it), focus goes to the heading of the section it was in.
 */
@Composable
fun ModalDialog(
    open: Boolean,
    title: String,
    onDismiss: () -> Unit,
    panelClass: String? = null,
    showTitle: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!open) return

    val titleId = panelClass?.let { "$it-title" } ?: DIALOG_TITLE_ID

    Dialog(attrs = {
        classes("dlg")
        panelClass?.let { classes(it) }
        if (showTitle) attr("aria-labelledby", titleId) else attr("aria-label", title)
        ref { element ->
            val dialog = element as HTMLDialogElement
            val returnTo = FocusReturn.from(dialog)
            if (!dialog.open) dialog.showModal()
            // Escape and the backdrop both fire `cancel`/`close` without touching our buttons, so
            // the caller has to hear about it or its `open` flag drifts out of step with reality.
            val onClose: (Event) -> Unit = { onDismiss() }
            dialog.addEventListener("close", onClose)
            onDispose {
                dialog.removeEventListener("close", onClose)
                if (dialog.open) dialog.close()
                returnTo.restore()
            }
        }
    }) {
        Div(attrs = { classes("dlg-body") }) {
            if (showTitle) {
                H2(attrs = {
                    classes("dlg-t")
                    attr("id", titleId)
                }) { Text(title) }
            }
            content()
        }
    }
}

/** Where focus was when a dialog opened, and where it goes back to when it closes. */
private class FocusReturn(
    private val opener: HTMLElement?,
    private val sectionHeading: HTMLElement?,
) {
    /**
     * After the update that closed the dialog has landed — the verb may remove its own opener in that
     * same update — and only if focus is then nowhere: focus something else took on purpose stays.
     */
    fun restore() {
        window.setTimeout({
            val anchor = opener ?: return@setTimeout
            if (!focusIsLost(anchor)) return@setTimeout
            when {
                opener.isConnected -> opener.focus()
                sectionHeading?.isConnected == true -> focusAsLanding(sectionHeading)
            }
        }, 0)
    }

    companion object {
        fun from(dialog: HTMLDialogElement): FocusReturn {
            val document = dialog.ownerDocument
            val opener = (document?.activeElement as? HTMLElement)?.takeIf { it != document?.body }
            val heading = opener?.closest("section")?.querySelector("h1, h2, h3") as? HTMLElement
            return FocusReturn(opener, heading)
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
        Button(kind = ButtonKind.Secondary, onClick = { onDismiss() }) { Text("Cancel") }
        Button(
            kind = ButtonKind.Primary,
            onClick = { if (confirmEnabled) onConfirm() },
            enabled = confirmEnabled,
        ) { Text(confirmLabel) }
    }
}

/** A paragraph inside a dialog: what the thing about to happen actually does. */
@Composable
fun DialogText(text: String) {
    P(attrs = { classes("dlg-p") }) { Text(text) }
}

/**
 * Shared by every dialog, because only one is ever open: `showModal()` makes the rest of the page
 * inert, so a second dialog cannot be reached to open it.
 */
internal const val DIALOG_TITLE_ID = "lu-dialog-title"
