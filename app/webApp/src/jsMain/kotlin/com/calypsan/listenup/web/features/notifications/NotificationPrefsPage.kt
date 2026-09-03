package com.calypsan.listenup.web.features.notifications

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.SwitchField
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Which notifications reach you, and how — one row per type, two channels each.
 *
 * Pure in [state]; the store wiring lives one level up. The rows come from the server's resolved
 * preferences rather than from a list this page holds, so a type added server-side appears here
 * with no edit — which is the whole reason the registry exists.
 *
 * ⛔ **Push is an account setting, not a browser one.** This tab cannot receive a push notification
 * — no service worker, no subscription, and none is wired — but the preference is stored per user
 * and governs the phone in your pocket. Turning it on here is therefore a real, useful act with an
 * effect you will not see in this window, which is exactly the kind of thing an interface has to
 * say out loud rather than let you discover. The section note says it.
 */
@Composable
fun NotificationPrefsPage(
    state: NotificationPrefsUiState,
    onSetPreference: (String, NotificationPreference) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Div(attrs = { classes("nprefs") }) {
        // Renders in every state, including the ones with nothing to toggle: a page that cannot
        // show what you asked for must still show the way out of it.
        Breadcrumb(trail = listOf("Settings", "Notifications"), onNavigate = { onOpenSettings() })
        H1(attrs = { classes("nprefs-title") }) { Text("Notifications") }

        when (state) {
            is NotificationPrefsUiState.Data -> {
                Rows(state.prefs, onSetPreference)
            }

            is NotificationPrefsUiState.Error -> {
                Div(attrs = { classes("empty") }) {
                    H3 { Text("These settings can't be loaded") }
                    // The typed error's own words. `AppError.message` is a user-facing constant
                    // per subtype, so it is printed rather than reworded here.
                    P { Text(state.error.message) }
                    Button(attrs = {
                        classes("btn-c")
                        attr("type", "button")
                        onClick { onRetry() }
                    }) { Text("Try again") }
                }
            }

            NotificationPrefsUiState.Loading -> {
                Div(attrs = { classes("skel", "nprefs-skel") })
            }
        }
    }
}

/**
 * The rows, and the sentence that explains the second column.
 *
 * Types this build cannot name get no row — a switch labelled `registration_approval` asks someone
 * to decide something they cannot read. When that filter empties the list entirely (a client older
 * than every type the server knows), the page says so instead of showing a bare heading.
 */
@Composable
private fun Rows(
    prefs: List<NotificationPreferenceDto>,
    onSetPreference: (String, NotificationPreference) -> Unit,
) {
    val known = prefs.filter { notificationTypeName(it.type) != null }
    if (known.isEmpty()) {
        Div(attrs = { classes("empty") }) {
            H3 { Text("Nothing to set yet") }
            P { Text("This server sends notification types your browser doesn't know about yet.") }
        }
        return
    }

    P(attrs = { classes("nprefs-note") }) {
        Text("In-app notifications appear in this browser. Push arrives on your phone or tablet — not here.")
    }

    Div(attrs = { classes("nprefs-list") }) {
        known.forEach { pref ->
            PrefRow(pref, onSetPreference)
        }
    }
}

/**
 * One type: what it is, and the two channels it can arrive on.
 *
 * A type the registry declares push-ineligible gets a disabled Push switch rather than a hidden
 * one. The column has to stay in the same place down the list, and a missing control reads as a
 * rendering fault where a dimmed one reads as a decision someone made.
 */
@Composable
private fun PrefRow(
    pref: NotificationPreferenceDto,
    onSetPreference: (String, NotificationPreference) -> Unit,
) {
    val name = notificationTypeName(pref.type) ?: return
    Div(attrs = { classes("nprefs-row") }) {
        Span(attrs = { classes("nprefs-name") }) { Text(name) }
        Div(attrs = { classes("nprefs-switches") }) {
            SwitchField(
                label = "In-app",
                checked = pref.preference.inApp,
                onChange = { on -> onSetPreference(pref.type, pref.preference.copy(inApp = on)) },
            )
            SwitchField(
                label = "Push",
                checked = pref.preference.push,
                enabled = pref.pushEligible,
                onChange = { on -> onSetPreference(pref.type, pref.preference.copy(push = on)) },
            )
        }
    }
}
