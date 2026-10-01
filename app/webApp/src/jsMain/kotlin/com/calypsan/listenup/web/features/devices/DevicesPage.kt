package com.calypsan.listenup.web.features.devices

import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.settings.DeviceRow
import com.calypsan.listenup.client.presentation.settings.DevicesUiState
import com.calypsan.listenup.client.util.relativeLastActive
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.PageHeader
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Where you are signed in, and how to stop being.
 *
 * The device you are reading this on is shown apart from the rest and carries **no revoke control**
 * — the same shape the Compose screen takes. Revoking your own session is signing yourself out,
 * which is a different intention from ending a session on a laptop you no longer have, and a button
 * that quietly does the first while looking like the second is a trap. "Sign out all other devices"
 * spares this one too, and its confirmation says so; signing this device out is the account menu's.
 */
@Composable
fun DevicesPage(
    state: DevicesUiState,
    nowMs: Long,
    onRevoke: (String) -> Unit,
    onSignOutOthers: () -> Unit,
    onRetry: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Div(attrs = { classes("dev") }) {
        PageHeader(title = "Devices")

        when (state) {
            is DevicesUiState.Loading -> {
                Div(attrs = { classes("skel", "dev-skel") })
            }

            is DevicesUiState.Error -> {
                // Only where retrying can actually work. `isRetryable` is the error hierarchy's
                // strict contract — false means the call needs the reader to DO something first,
                // so a button that just re-fires it is a dead end dressed as a way out. An
                // expired session is the case that made this visible: the message says to sign
                // in again, directly above a button that can only fail again.
                EmptyState(title = "Could not load your devices", body = state.error.message) {
                    if (state.error.isRetryable) {
                        Button(kind = ButtonKind.Primary, onClick = { onRetry() }) { Text("Try again") }
                    }
                }
            }

            is DevicesUiState.Ready -> {
                val current = state.devices.firstOrNull { it.isCurrent }
                val others = state.devices.filter { !it.isCurrent }

                current?.let { device ->
                    Div(attrs = { classes("dev-section") }) {
                        H2(attrs = { classes("dev-section-h") }) { Text("This device") }
                        DeviceCard(device, nowMs, revoking = false, onRevoke = null)
                    }
                }

                Div(attrs = { classes("dev-section") }) {
                    H2(attrs = { classes("dev-section-h") }) { Text("Other devices") }
                    if (others.isEmpty()) {
                        EmptyState(title = "You are not signed in anywhere else.", look = EmptyLook.Inline)
                    } else {
                        others.forEach { device ->
                            key(device.sessionId) {
                                DeviceCard(
                                    device = device,
                                    nowMs = nowMs,
                                    revoking = device.sessionId in state.signingOut,
                                    onRevoke = { onRevoke(device.sessionId) },
                                )
                            }
                        }
                    }
                }

                Div(attrs = { classes("dev-danger") }) {
                    Button(kind = ButtonKind.Secondary, onClick = { confirming = true }) {
                        Text("Sign out all other devices")
                    }
                }

                ConfirmDialog(
                    open = confirming,
                    title = "Sign out all other devices?",
                    // Names the consequence people actually care about: which device survives.
                    body = "Every other device is signed out. This one stays signed in.",
                    confirmLabel = "Sign out all other devices",
                    onConfirm = {
                        confirming = false
                        onSignOutOthers()
                    },
                    onDismiss = { confirming = false },
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceRow,
    nowMs: Long,
    revoking: Boolean,
    onRevoke: (() -> Unit)?,
) {
    Div(attrs = { classes("dev-card") }) {
        Div(attrs = { classes("dev-card-text") }) {
            Span(attrs = { classes("dev-card-t") }) { Text(device.displayName) }
            device.secondary.takeIf { it.isNotBlank() }?.let {
                Span(attrs = { classes("dev-card-sub") }) { Text(it) }
            }
            Span(attrs = { classes("dev-card-when") }) {
                Text(if (device.isCurrent) "Active now" else relativeLastActive(device.lastUsedAt, nowMs))
            }
        }
        onRevoke?.let { revoke ->
            Button(
                kind = ButtonKind.Secondary,
                onClick = { revoke() },
                label = "Sign out ${device.displayName}",
                attrs = {
                    // Disabled while its own revoke is in flight, so a second press cannot queue a
                    // second call for a session that is already going.
                    if (revoking) attr("disabled", "")
                },
            ) { Text(if (revoking) "Signing out…" else "Sign out") }
        }
    }
}
