package com.calypsan.listenup.web.features.nowplaying

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.presentation.player.PlayerUiState
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import com.calypsan.listenup.web.design.Button as DsButton

/**
 * The cause of a problem and Dismiss, inside Now Playing. The bar says what is wrong and offers the way out; this
 * carries the sentence the bar has no room for, and Dismiss (§4.3: dismiss keeps the book loaded, paused). PR 3's
 * error card replaces it.
 *
 * Retry is offered when, and only when, the main button would retry ([PrimaryControl.Retry]): a dropped
 * connection or a prepare that failed can be tried again, but a held book's or a removed book's refusal
 * cannot, and a Retry there would be a control that cannot keep its promise.
 */
@Composable
internal fun PlayerProblemNote(
    error: AppError?,
    primary: PrimaryControl?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (error == null) return

    Div(attrs = {
        classes("np-note")
        attr("role", "alert")
    }) {
        Span(attrs = { classes("np-note-t") }) {
            Text(error.playerTitle())
            error.playerCause()?.let { cause -> Text(" $cause") }
        }
        if (primary == PrimaryControl.Retry) {
            DsButton(kind = ButtonKind.Secondary, size = ButtonSize.Sm, onClick = onRetry) { Text("Retry") }
        }
        Button(attrs = {
            classes("np-note-x")
            attr("type", "button")
            attr("aria-label", "Dismiss")
            attr("title", "Dismiss")
            onClick { onDismiss() }
        }) {
            Icon(WebIcon.X, size = NOTE_ICON_SIZE)
        }
    }
}

/** The error the note reports for [state]: a failure, or a known reason the book cannot play. */
internal fun problemErrorOf(state: PlayerUiState): AppError? {
    val phase = (state as? PlayerUiState.Loaded)?.phase
    return (phase as? PlayerPhase.Failed)?.error ?: (phase as? PlayerPhase.Unavailable)?.reason
}

private const val NOTE_ICON_SIZE = 15
