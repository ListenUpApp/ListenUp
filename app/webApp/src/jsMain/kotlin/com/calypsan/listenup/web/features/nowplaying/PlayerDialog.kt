package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.Dialog
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.ContentBuilder
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * The shell every player panel wears: a real modal, a heading it is labelled by, and a way out.
 *
 * Web's answer to `PlayerPanelScaffold`, which does the same job for the native clients. Extracted
 * when the third panel arrived: chapters, the sleep timer and speed were each hand-rolling the
 * `showModal()` call, the `aria-labelledby` wiring and the `close` listener, which is three places
 * for a focus trap to be subtly different in.
 *
 * The real `<dialog>` with [HTMLDialogElement.showModal] is what buys the focus trap, the inert
 * page behind it and Escape-to-close — none of which a `div` with `role="dialog"` gets for free,
 * and all of which it would reimplement badly.
 *
 * Every panel is labelled by its own heading rather than by an `aria-label` repeating the title,
 * so the accessible name cannot drift from the visible one.
 */
@Composable
internal fun PlayerDialog(
    open: Boolean,
    title: String,
    /** The panel's own class, added beside `dlg` — its width and spacing live there. */
    panelClass: String,
    onDismiss: () -> Unit,
    content: ContentBuilder<HTMLElement>,
) {
    if (!open) return

    val titleId = "$panelClass-title"

    Dialog(attrs = {
        classes("dlg", panelClass)
        attr("aria-labelledby", titleId)
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
            attr("id", titleId)
        }) { Text(title) }

        content()

        Button(attrs = {
            classes("btn-ghost")
            attr("type", "button")
            onClick { onDismiss() }
        }) {
            Icon(WebIcon.X, size = CLOSE_ICON_SIZE)
            Text("Close")
        }
    }
}

private const val CLOSE_ICON_SIZE = 17
