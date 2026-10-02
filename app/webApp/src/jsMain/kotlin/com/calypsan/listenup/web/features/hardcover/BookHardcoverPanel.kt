package com.calypsan.listenup.web.features.hardcover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.KeepOffRemoves
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.SwitchField
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Book Detail's Hardcover panel (spec B5), in the side rail between Details and Readers: the book's
 * match, where it stands, Change match and Remove match — or "Needs a match" with Find on Hardcover.
 * Silent — no panel at all — when [state] is hidden: not connected, or not known yet. Sync with
 * Hardcover (#1541) heads the panel in every other state; a book never matched is that switch alone,
 * and a book kept off is that switch and one line.
 *
 * Right after a pick the status line reads "Matched just now", for the minute the ViewModel says so.
 */
@Composable
fun BookHardcoverPanel(
    state: BookHardcoverUiState,
    onFindMatch: () -> Unit,
    onRemoveMatch: () -> Unit,
    onSetSynced: (Boolean) -> Unit,
) {
    when (state) {
        BookHardcoverUiState.Hidden -> {
            Unit
        }

        // Decision 1: a book never matched is the switch alone, so it can be kept off before its first listen.
        BookHardcoverUiState.Unmatched -> {
            Panel(title = PANEL_TITLE) {
                Div(attrs = { classes(BOOK) }) {
                    SyncSwitch(isOn = true, keepOffRemoves = null, divided = false, onSetSynced = onSetSynced)
                }
            }
        }

        BookHardcoverUiState.NeedsMatch -> {
            Panel(title = PANEL_TITLE) {
                Div(attrs = { classes(BOOK) }) {
                    SyncSwitch(isOn = true, keepOffRemoves = null, divided = true, onSetSynced = onSetSynced)
                    Span(attrs = { classes("hc-match-title") }) { Text("Needs a match") }
                    P(attrs = { classes("hc-lede") }) { Text("Pick the right book so your listening syncs.") }
                    Button(kind = ButtonKind.Primary, onClick = onFindMatch, attrs = { classes("hc-book-act") }) {
                        Icon(WebIcon.Search, size = BUTTON_ICON)
                        Text("Find on Hardcover")
                    }
                }
            }
        }

        is BookHardcoverUiState.Linked -> {
            Panel(title = "On Hardcover") {
                Div(attrs = { classes(BOOK) }) {
                    SyncSwitch(
                        isOn = true,
                        keepOffRemoves = state.keepOffRemoves,
                        divided = true,
                        onSetSynced = onSetSynced,
                    )
                    Span(attrs = { classes("hc-match-title") }) { Text(state.match.title ?: "Matched on Hardcover") }
                    matchedLine(state.match)?.let { Span(attrs = { classes("hc-match-by") }) { Text(it) } }
                    if (state.match.chosenByYou) Span(attrs = { classes("hc-tell") }) { Text("Matched by you") }
                    SyncLine(state)
                    Div(attrs = { classes("hc-actions", "hc-book-act") }) {
                        Button(kind = ButtonKind.Secondary, onClick = onFindMatch) { Text("Change match") }
                        Button(kind = ButtonKind.Secondary, onClick = onRemoveMatch) { Text("Remove match") }
                    }
                }
            }
        }

        is BookHardcoverUiState.KeptOff -> {
            Panel(title = PANEL_TITLE) {
                Div(attrs = { classes(BOOK) }) {
                    // While syncing again saves, the switch already reads on and the line has gone.
                    SyncSwitch(
                        isOn = state.isResuming,
                        keepOffRemoves = null,
                        divided = false,
                        onSetSynced = onSetSynced,
                    )
                    if (!state.isResuming) {
                        // en.json's `hardcover.kept_off_line`.
                        P(attrs = { classes("hc-kept-off") }) {
                            Text("Kept off Hardcover — nothing about this book is shared or brought in.")
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Sync with Hardcover" (#1541), heading the panel. Off asks first — the shared modal — only when
 * [keepOffRemoves] says something visible would leave; the switch, fully controlled, stays on behind it
 * until Keep off. [divided] draws the hairline between it and the match beneath.
 */
@Composable
private fun SyncSwitch(
    isOn: Boolean,
    keepOffRemoves: KeepOffRemoves?,
    divided: Boolean,
    onSetSynced: (Boolean) -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    Div(attrs = {
        classes("hc-book-switch")
        if (divided) classes("is-divided")
    }) {
        // en.json's `hardcover.keep_off_switch`.
        SwitchField(
            label = "Sync with Hardcover",
            checked = isOn,
            onChange = { on -> if (!on && keepOffRemoves != null) asking = true else onSetSynced(on) },
        )
    }
    ConfirmDialog(
        open = asking && keepOffRemoves != null,
        // en.json's `hardcover.keep_off_confirm_title` and `keep_off_confirm_action`.
        title = "Keep this book off Hardcover?",
        body = keepOffRemoves?.confirmBody().orEmpty(),
        confirmLabel = "Keep off",
        onConfirm = {
            asking = false
            onSetSynced(false)
        },
        onDismiss = { asking = false },
    )
}

/** en.json's `hardcover.keep_off_confirm_body*`: only what will actually leave. No `else`: a new kind must pick its words. */
private fun KeepOffRemoves.confirmBody(): String =
    when (this) {
        KeepOffRemoves.READS -> {
            "Its Hardcover reads leave Readers here. Nothing on Hardcover changes."
        }

        KeepOffRemoves.TO_READ -> {
            "It comes off your To Read shelf. Nothing on Hardcover changes."
        }

        KeepOffRemoves.READS_AND_TO_READ -> {
            "Its Hardcover reads leave Readers here and it comes off your To Read shelf. Nothing on Hardcover changes."
        }
    }

/** Where the match stands, as a polite live region: "Matched just now" first, then its sync state. */
@Composable
private fun SyncLine(state: BookHardcoverUiState.Linked) {
    val (icon, words) =
        if (state.justMatched) WebIcon.Check to "Matched just now" else state.sync.icon() to state.sync.words()
    Span(attrs = {
        classes("hc-book-sync")
        if (!state.justMatched && state.sync == HardcoverBookSync.REMOVED_ON_HARDCOVER) classes("is-paused")
        attr("role", "status")
    }) {
        Span(attrs = {
            classes("hc-book-sync-i")
            attr("aria-hidden", "true")
        }) { Icon(icon, size = BUTTON_ICON) }
        Span { Text(words) }
    }
}

// Deliberately no `else`: a new state must fail to compile here rather than borrow another's words.
private fun HardcoverBookSync.words(): String =
    when (this) {
        HardcoverBookSync.UP_TO_DATE -> "Up to date"
        HardcoverBookSync.WAITING -> "Updating…"
        HardcoverBookSync.NOTHING_SENT_YET -> "Not sent yet"
        HardcoverBookSync.REMOVED_ON_HARDCOVER -> "Stopped — you removed it on Hardcover"
    }

private fun HardcoverBookSync.icon(): WebIcon =
    when (this) {
        HardcoverBookSync.UP_TO_DATE -> WebIcon.Check
        HardcoverBookSync.WAITING, HardcoverBookSync.NOTHING_SENT_YET -> WebIcon.Clock
        HardcoverBookSync.REMOVED_ON_HARDCOVER -> WebIcon.Alert
    }

private const val BUTTON_ICON = 16

/** The panel's body, in every state. */
private const val BOOK = "hc-book"

/** The panel's title in every state but matched, which reads "On Hardcover". */
private const val PANEL_TITLE = "Hardcover"
