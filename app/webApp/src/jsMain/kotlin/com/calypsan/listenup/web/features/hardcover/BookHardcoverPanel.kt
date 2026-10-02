package com.calypsan.listenup.web.features.hardcover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.hardcover.HardcoverBookSync
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Book Detail's Hardcover panel (spec B5), in the side rail between Details and Readers: the book's
 * match, where it stands, Change match and Remove match — or "Needs a match" with Find on Hardcover.
 * Silent — no panel at all — when [state] is hidden, as Readers and Ratings are when they have
 * nothing to say: not connected, never matched, or not known yet.
 *
 * Right after a pick the status line reads "Matched just now", for the minute the ViewModel says so.
 */
@Composable
fun BookHardcoverPanel(
    state: BookHardcoverUiState,
    onFindMatch: () -> Unit,
    onRemoveMatch: () -> Unit,
) {
    when (state) {
        BookHardcoverUiState.Hidden -> {
            Unit
        }

        BookHardcoverUiState.NeedsMatch -> {
            Panel(title = "Hardcover") {
                Div(attrs = { classes("hc-book") }) {
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
                Div(attrs = { classes("hc-book") }) {
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

        // Task 18 draws the kept-off panel, and the switch alone for a book never matched.
        is BookHardcoverUiState.KeptOff, BookHardcoverUiState.Unmatched -> {
            Unit
        }
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
