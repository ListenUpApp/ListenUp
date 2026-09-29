package com.calypsan.listenup.web.features.auth

import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.LinkButton
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Shown after registering, while an admin decides. Pure in [state].
 *
 * The approval watch is a server-pushed stream, and a stream can die without saying so — hence the
 * manual re-check. Never Stranded applies to waiting rooms too: the user must always have a way to
 * ask again rather than trusting a socket that may already be gone.
 */
@Composable
fun PendingApprovalPanel(
    state: PendingApprovalUiState,
    email: String,
    onCheckStatus: () -> Unit,
    onCancel: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    Div(attrs = { classes("auth-fields") }) {
        when (state) {
            is PendingApprovalUiState.Waiting -> {
                P {
                    Text("We've asked the server's admin to approve ")
                    Span(attrs = { classes("mono") }) { Text(email) }
                    Text(". You'll be able to sign in as soon as they do.")
                }
                Button(kind = ButtonKind.Secondary, onClick = { onCheckStatus() }) {
                    Icon(WebIcon.Clock, size = BUTTON_ICON_SIZE)
                    Text("Check again")
                }
                Div(attrs = { classes("auth-alt") }) {
                    LinkButton("Cancel this request", onClick = onCancel)
                }
            }

            is PendingApprovalUiState.Approved -> {
                P { Text("You're approved. Sign in to start listening.") }
                Button(kind = ButtonKind.Primary, size = ButtonSize.Lg, onClick = { onAcknowledge() }, fill = true) {
                    Icon(WebIcon.LogIn, size = BUTTON_ICON_SIZE)
                    Text("Sign in")
                }
            }

            is PendingApprovalUiState.Denied -> {
                FormAlert(state.message)
                Div(attrs = { classes("auth-alt") }) {
                    LinkButton("Back to sign in", onClick = onCancel)
                }
            }
        }
    }
}

private const val BUTTON_ICON_SIZE = 19
