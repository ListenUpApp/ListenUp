package com.calypsan.listenup.web.features.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.settings.DeviceRow
import com.calypsan.listenup.client.presentation.settings.DevicesUiState
import com.calypsan.listenup.client.util.relativeLastActive
import com.calypsan.listenup.web.design.ConfirmDialog
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Where you are signed in, and how to stop being.
 *
 * The device you are reading this on is shown apart from the rest and carries **no revoke control**
 * — the same shape the Compose screen takes. Revoking your own session is signing yourself out,
 * which is a different intention from ending a session on a laptop you no longer have, and a button
 * that quietly does the first while looking like the second is a trap. "Sign out everywhere" is the
 * deliberate way to include this one, and it says so.
 */
@Composable
fun DevicesPage(
    state: DevicesUiState,
    nowMs: Long,
    onRevoke: (String) -> Unit,
    onSignOutEverywhere: () -> Unit,
    onRetry: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Div(attrs = { classes("dev") }) {
        H1(attrs = { classes("dev-title") }) { Text("Devices") }

        when (state) {
            is DevicesUiState.Loading -> {
                Div(attrs = { classes("skel", "dev-skel") })
            }

            is DevicesUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H2 { Text("Could not load your devices") }
                    P { Text(state.error.message) }
                    Button(attrs = {
                        classes("btn")
                        attr("type", "button")
                        onClick { onRetry() }
                    }) { Text("Try again") }
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
                        P(attrs = { classes("dev-none") }) {
                            Text("You are not signed in anywhere else.")
                        }
                    } else {
                        others.forEach { device ->
                            DeviceCard(
                                device = device,
                                nowMs = nowMs,
                                revoking = device.sessionId in state.signingOut,
                                onRevoke = { onRevoke(device.sessionId) },
                            )
                        }
                    }
                }

                Div(attrs = { classes("dev-danger") }) {
                    Button(attrs = {
                        classes("btn-o")
                        attr("type", "button")
                        onClick { confirming = true }
                    }) { Text("Sign out everywhere") }
                }

                ConfirmDialog(
                    open = confirming,
                    title = "Sign out everywhere?",
                    // Names the consequence people actually care about: this one included.
                    body =
                        "Every device is signed out, including this one. " +
                            "Downloaded books stay on the devices that have them.",
                    confirmLabel = "Sign out everywhere",
                    onConfirm = {
                        confirming = false
                        onSignOutEverywhere()
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
            Button(attrs = {
                classes("btn-o")
                attr("type", "button")
                attr("aria-label", "Sign out ${device.displayName}")
                // Disabled while its own revoke is in flight, so a second press cannot queue a
                // second call for a session that is already going.
                if (revoking) attr("disabled", "")
                onClick { revoke() }
            }) { Text(if (revoking) "Signing out…" else "Sign out") }
        }
    }
}
