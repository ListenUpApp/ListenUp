package com.calypsan.listenup.web.features.auth

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState
import com.calypsan.listenup.web.design.Icon
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
                Button(attrs = {
                    classes("btn-ghost")
                    attr("type", "button")
                    onClick { onCheckStatus() }
                }) {
                    Icon(WebIcon.Clock, size = BUTTON_ICON_SIZE)
                    Text("Check again")
                }
                Div(attrs = { classes("auth-alt") }) {
                    Span(attrs = {
                        classes("lnk")
                        onClick { onCancel() }
                    }) { Text("Cancel this request") }
                }
            }

            is PendingApprovalUiState.Approved -> {
                P { Text("You're approved. Sign in to start listening.") }
                Button(attrs = {
                    classes("btn")
                    attr("type", "button")
                    onClick { onAcknowledge() }
                }) {
                    Icon(WebIcon.LogIn, size = BUTTON_ICON_SIZE)
                    Text("Sign in")
                }
            }

            is PendingApprovalUiState.Denied -> {
                Div(attrs = { classes("auth-err") }) { Text(state.message) }
                Div(attrs = { classes("auth-alt") }) {
                    Span(attrs = {
                        classes("lnk")
                        onClick { onCancel() }
                    }) { Text("Back to sign in") }
                }
            }
        }
    }
}

private const val BUTTON_ICON_SIZE = 19
