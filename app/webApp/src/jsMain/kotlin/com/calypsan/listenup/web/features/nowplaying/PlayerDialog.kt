package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.ModalDialog
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Text

/**
 * The shell every player panel wears: the app's one [ModalDialog], plus a way out at the foot.
 *
 * Web's answer to `PlayerPanelScaffold`, which does the same job for the native clients. It used to
 * hand-roll its own `showModal()` call, `aria-labelledby` wiring and `close` listener — a second
 * copy of [ModalDialog]'s, and so a second place for a focus trap or an Escape to be subtly wrong.
 * Now the only thing it adds is the Close button, because every player panel is a place you visit
 * and leave rather than a question you answer.
 */
@Composable
internal fun PlayerDialog(
    open: Boolean,
    title: String,
    /** The panel's own class, added beside `dlg` — its width and spacing live there. */
    panelClass: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalDialog(open = open, title = title, onDismiss = onDismiss, panelClass = panelClass) {
        content()

        Button(
            kind = ButtonKind.Secondary,
            onClick = { onDismiss() },
            attrs = {
                classes("dlg-close")
            },
        ) {
            Icon(WebIcon.X, size = CLOSE_ICON_SIZE)
            Text("Close")
        }
    }
}

private const val CLOSE_ICON_SIZE = 17
