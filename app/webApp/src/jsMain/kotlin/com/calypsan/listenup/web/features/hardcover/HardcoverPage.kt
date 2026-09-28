package com.calypsan.listenup.web.features.hardcover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.hardcover.HardcoverBrokenReason
import com.calypsan.listenup.api.dto.hardcover.HardcoverLinkFailure
import com.calypsan.listenup.client.presentation.settings.HardcoverSettingsUiState
import com.calypsan.listenup.client.util.formatDateLong
import com.calypsan.listenup.web.copyToClipboard
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.disabledWhen
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Ol
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/**
 * Settings → Account → Hardcover: connect with Hardcover's device sign-in, watch it complete, see
 * who you are connected as, disconnect, and reconnect a broken connection.
 *
 * Pure in [state]; the session wiring — and the best-effort automatic open of the approval page —
 * lives one level up. Every string is the English text of en.json's `hardcover` group.
 *
 * ⛔ **The approval page is a real link, always.** The automatic open follows an awaited RPC, so a
 * browser's popup blocker is entitled to refuse it. The Linking phase therefore never depends on it:
 * "Open Hardcover in a new tab" is an `<a target="_blank">` a person clicks, which no blocker stops.
 *
 * ⛔ **Copying can fail without stranding anyone.** `navigator.clipboard` is missing on a plain-http
 * LAN server, which is most of these. The code is on screen and selectable whatever the clipboard
 * says; "Copied" appears only when the copy actually landed.
 *
 * @param copyText Puts text on the clipboard and reports whether it got there. Specs replace it,
 *   because a headless browser's clipboard answers depend on permissions this page does not own.
 */
@Composable
fun HardcoverPage(
    state: HardcoverSettingsUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    copyText: (String, (Boolean) -> Unit) -> Unit = ::copyToClipboard,
) {
    Div(attrs = { classes("hc") }) {
        // Settings has no Account page of its own on web — Account is a section of Settings — so
        // both crumbs lead there. The trail still names the section the entry lives in.
        Breadcrumb(trail = listOf("Settings", "Account", SCREEN_TITLE), onNavigate = { onOpenSettings() })

        when (state) {
            HardcoverSettingsUiState.Loading -> {
                Title(SCREEN_TITLE)
                P(attrs = {
                    classes(LEDE)
                    attr("role", "status")
                }) { Text("Checking your Hardcover connection…") }
                Div(attrs = { classes("skel", "hc-skel") })
            }

            HardcoverSettingsUiState.NotOffered -> {
                Title(SCREEN_TITLE)
                P(attrs = { classes(LEDE) }) { Text("Hardcover isn't set up on this server.") }
            }

            is HardcoverSettingsUiState.NotConnected -> {
                NotConnected(state, onConnect)
            }

            is HardcoverSettingsUiState.Linking -> {
                Linking(state, onCancel = onDisconnect, copyText = copyText)
            }

            is HardcoverSettingsUiState.Connected -> {
                Connected(state, onDisconnect)
            }

            is HardcoverSettingsUiState.Broken -> {
                Broken(state, onConnect, onDisconnect)
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    H1(attrs = { classes("hc-title") }) { Text(text) }
}

@Composable
private fun NotConnected(
    state: HardcoverSettingsUiState.NotConnected,
    onConnect: () -> Unit,
) {
    Div(attrs = { classes("hc-cols") }) {
        Section(attrs = { classes(CARD, "hc-hero") }) {
            Span(attrs = { classes("hc-glyph") }) { Icon(WebIcon.Book, size = GLYPH_ICON) }
            Title("Share what you finish")
            P(attrs = { classes(LEDE) }) {
                Text("Connect your Hardcover account and ListenUp will mark what you listen to as read there.")
            }
        }

        Section(attrs = { classes(CARD) }) {
            Ul(attrs = { classes("hc-list") }) {
                ListItem(WebIcon.Check, "A book you finish is marked as read on Hardcover.")
                ListItem(WebIcon.Lock, "You sign in on Hardcover itself. ListenUp never sees your password.")
            }
            state.lastFailure?.let { failure ->
                // Informational rather than alarming: the answer to every one is to connect again.
                P(attrs = {
                    classes("hc-note")
                    attr("role", "status")
                }) { Text(failure.message()) }
            }
            Div(attrs = { classes("hc-actions") }) {
                Button(attrs = {
                    classes("btn-c")
                    attr("type", TYPE_BUTTON)
                    disabledWhen(state.isStarting)
                    if (state.isStarting) attr("aria-busy", "true")
                    onClick { onConnect() }
                }) { Text("Connect Hardcover") }
            }
        }
    }
}

@Composable
private fun ListItem(
    icon: WebIcon,
    text: String,
) {
    Li(attrs = { classes("hc-item") }) {
        Span(attrs = {
            classes("hc-item-i")
            attr(ARIA_HIDDEN, "true")
        }) { Icon(icon, size = ITEM_ICON) }
        Span { Text(text) }
    }
}

@Composable
private fun Linking(
    state: HardcoverSettingsUiState.Linking,
    onCancel: () -> Unit,
    copyText: (String, (Boolean) -> Unit) -> Unit,
) {
    val address = state.verificationUri.withoutScheme()
    // Reset per code: a fresh code that reads "Copied" would claim a copy that never happened.
    var copied by remember(state.userCode) { mutableStateOf(false) }

    Header(attrs = { classes("hc-head") }) {
        Title("Approve ListenUp on Hardcover")
        P(attrs = { classes(LEDE) }) {
            Text("We opened the page for you. On another device, go to $address and enter this code.")
        }
    }

    Div(attrs = { classes("hc-cols") }) {
        Section(attrs = {
            classes(CARD, "hc-code-card")
            attr("aria-labelledby", CODE_HEADING_ID)
        }) {
            H2(attrs = {
                classes("hc-label")
                id(CODE_HEADING_ID)
            }) { Text("Your code") }
            Span(attrs = { classes("hc-code", "mono") }) { Text(state.userCode) }
            Button(attrs = {
                classes(BUTTON_OUTLINED, "hc-copy")
                attr("type", TYPE_BUTTON)
                onClick { copyText(state.userCode) { landed -> copied = landed } }
            }) {
                if (copied) {
                    Icon(WebIcon.Check, size = ITEM_ICON)
                    Text("Copied")
                } else {
                    Text("Copy code")
                }
            }
        }

        Section(attrs = { classes(CARD) }) {
            Ol(attrs = { classes("hc-steps") }) {
                Step(1) {
                    Text("Open ")
                    B { Text(address) }
                }
                Step(2) { Text("Enter the code and approve ListenUp") }
                Step(STEP_THREE) { Text("This page updates on its own") }
            }
            A(href = state.verificationUriComplete, attrs = {
                classes("btn-c", "hc-open")
                attr("target", "_blank")
                attr("rel", "noopener noreferrer")
            }) {
                Text("Open Hardcover in a new tab")
                Icon(WebIcon.ArrowRight, size = ITEM_ICON)
            }
        }
    }

    Div(attrs = {
        classes("hc-wait")
        attr("role", "status")
        attr("aria-live", "polite")
    }) {
        Span(attrs = {
            classes("hc-spin")
            attr(ARIA_HIDDEN, "true")
        })
        Span(attrs = { classes("hc-wait-text") }) {
            B { Text("Waiting for you to approve") }
            Span { Text("This screen updates by itself. The code works for about 15 minutes.") }
        }
        // No confirmation: cancelling a code nobody has approved loses nothing.
        Button(attrs = {
            classes(BUTTON_OUTLINED)
            attr("type", TYPE_BUTTON)
            onClick { onCancel() }
        }) { Text("Cancel") }
    }
}

@Composable
private fun Step(
    number: Int,
    content: @Composable () -> Unit,
) {
    Li(attrs = { classes("hc-step") }) {
        Span(attrs = {
            classes("hc-step-n")
            attr(ARIA_HIDDEN, "true")
        }) { Text(number.toString()) }
        Span { content() }
    }
}

@Composable
private fun Connected(
    state: HardcoverSettingsUiState.Connected,
    onDisconnect: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Title(SCREEN_TITLE)
    Div(attrs = { classes("hc-cols") }) {
        Section(attrs = { classes(CARD, "hc-hero", "hc-who") }) {
            Span(attrs = {
                classes("hc-avatar")
                attr(ARIA_HIDDEN, "true")
            }) { Text(state.username.take(1).uppercase()) }
            Div(attrs = { classes("hc-who-text") }) {
                Span(attrs = { classes("hc-badge") }) {
                    Icon(WebIcon.Check, size = BADGE_ICON)
                    Text("Connected")
                }
                H2(attrs = { classes("hc-user") }) { Text(state.username) }
                Span(attrs = { classes("hc-since") }) { Text("Since ${formatDateLong(state.since)}") }
            }
        }

        Section(attrs = { classes(CARD) }) {
            H2(attrs = { classes("hc-card-h") }) { Text("What ListenUp shares") }
            Ul(attrs = { classes("hc-list") }) {
                ListItem(WebIcon.Check, "Books you finish, marked as read")
            }
        }
    }

    Div(attrs = { classes("hc-actions") }) {
        Button(attrs = {
            classes(BUTTON_OUTLINED)
            attr("type", TYPE_BUTTON)
            disabledWhen(state.isDisconnecting)
            onClick { confirming = true }
        }) { Text("Disconnect") }
    }

    DisconnectConfirm(open = confirming, onDisconnect = onDisconnect, onDismiss = { confirming = false })
}

@Composable
private fun Broken(
    state: HardcoverSettingsUiState.Broken,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Title(SCREEN_TITLE)
    Section(attrs = { classes(CARD, "hc-broken") }) {
        Span(attrs = {
            classes("hc-glyph")
            attr(ARIA_HIDDEN, "true")
        }) { Icon(WebIcon.X, size = GLYPH_ICON) }
        Div(attrs = { classes("hc-broken-text") }) {
            H2(attrs = { classes("hc-card-h") }) { Text("Reconnect to keep sharing") }
            P(attrs = { classes(LEDE) }) { Text(state.reason.message()) }
            state.username?.let { username ->
                P(attrs = { classes(LEDE) }) {
                    Text("Was connected as ")
                    B { Text(username) }
                    Text(".")
                }
            }
            Div(attrs = { classes("hc-actions") }) {
                Button(attrs = {
                    classes("btn-c")
                    attr("type", TYPE_BUTTON)
                    disabledWhen(state.isStarting)
                    if (state.isStarting) attr("aria-busy", "true")
                    onClick { onReconnect() }
                }) { Text("Reconnect") }
                Button(attrs = {
                    classes(BUTTON_OUTLINED)
                    attr("type", TYPE_BUTTON)
                    onClick { confirming = true }
                }) { Text("Disconnect") }
            }
        }
    }

    DisconnectConfirm(open = confirming, onDisconnect = onDisconnect, onDismiss = { confirming = false })
}

/** The one question before a connection ends: web's shared confirm, never `window.confirm`. */
@Composable
private fun DisconnectConfirm(
    open: Boolean,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        open = open,
        title = "Disconnect Hardcover?",
        body = "ListenUp will stop updating your Hardcover account. Nothing already on Hardcover is removed.",
        confirmLabel = "Disconnect",
        onConfirm = {
            onDismiss()
            onDisconnect()
        },
        onDismiss = onDismiss,
    )
}

/** Why the last attempt ended — en.json's `hardcover.failure_*`. */
private fun HardcoverLinkFailure.message(): String =
    when (this) {
        HardcoverLinkFailure.DENIED -> "You declined on Hardcover. Connect again whenever you like."
        HardcoverLinkFailure.EXPIRED -> "The code expired before it was approved. Try again."
        HardcoverLinkFailure.UNREACHABLE -> "Hardcover couldn't be reached. Try again."
    }

/** Why a connection needs a reconnect — en.json's `hardcover.broken_*`. */
private fun HardcoverBrokenReason.message(): String =
    when (this) {
        HardcoverBrokenReason.REVOKED -> {
            "Hardcover no longer accepts ListenUp's access — it may have been removed on Hardcover. " +
                "Reconnect to continue."
        }

        HardcoverBrokenReason.CANNOT_DECRYPT -> {
            "This server can't read its saved Hardcover sign-in, which usually means it was restored " +
                "from a backup. Reconnect to continue."
        }

        HardcoverBrokenReason.MISSING_SCOPE -> {
            "ListenUp is missing a permission it needs on Hardcover. Reconnect to grant it."
        }
    }

/** `hardcover.app/link` — the address a person types, without the scheme nobody types. */
private fun String.withoutScheme(): String = substringAfter("://")

private const val TYPE_BUTTON = "button"
private const val ARIA_HIDDEN = "aria-hidden"
private const val BUTTON_OUTLINED = "btn-o"
private const val CARD = "hc-card"
private const val LEDE = "hc-lede"

/** en.json's `hardcover.screen_title` — also the breadcrumb's last step. */
private const val SCREEN_TITLE = "Hardcover"
private const val CODE_HEADING_ID = "hc-code-h"
private const val STEP_THREE = 3
private const val GLYPH_ICON = 28
private const val ITEM_ICON = 18
private const val BADGE_ICON = 14
