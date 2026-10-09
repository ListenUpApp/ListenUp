package com.calypsan.listenup.web.features.match

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.presentation.bookdetail.LastMatchEvent
import com.calypsan.listenup.client.presentation.bookdetail.LastMatchUi
import com.calypsan.listenup.client.presentation.match.MatchReceiptUiState
import com.calypsan.listenup.client.util.relativeLastActiveInSentence
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.Icon
import com.calypsan.listenup.web.design.WebIcon
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Text

/**
 * Book Detail's last-match row (A-04, on the web): "Details matched 3 days ago · See what changed · Undo last match",
 * while the book's last match can still be undone. See what changed opens the receipt's own dialog of changes;
 * a failed Undo says why under the sentence and the row stays to try again.
 *
 * Once Undo settles, [outcome] (Undone or Expired) takes the row's place in the receipt's own status region — it
 * is announced, takes focus, and stays until dismissed, exactly as the receipt's Undo does.
 */
@Suppress("LongParameterList")
@Composable
fun LastMatchRow(
    lastMatch: LastMatchUi?,
    outcome: MatchReceiptUiState,
    nowMs: Long,
    onSeeWhatChanged: () -> Unit,
    onCloseWhatChanged: () -> Unit,
    onUndo: () -> Unit,
    onDismissOutcome: () -> Unit,
) {
    if (outcome !is MatchReceiptUiState.None) {
        MatchReceiptRegion(state = outcome, onUndo = {}, onDismiss = onDismissOutcome)
        return
    }
    val row = lastMatch ?: return
    Section(attrs = {
        classes("bmx-last")
        attr("aria-label", "Last match")
    }) {
        Icon(WebIcon.Check, size = SMALL_ICON)
        Div(attrs = { classes("bmx-last-text") }) {
            P(attrs = { classes("bmx-last-t") }) { Text(lastMatchSentence(row, nowMs)) }
            row.undoError?.let { error ->
                P(attrs = {
                    classes("bmx-last-err")
                    attr("role", "alert")
                }) { Text(nothingChanged(error.message)) }
            }
        }
        Div(attrs = { classes("bmx-last-acts") }) {
            Button(kind = ButtonKind.Ghost, size = ButtonSize.Sm, onClick = onSeeWhatChanged) {
                Text("See what changed")
            }
            // aria-disabled while it runs: the press must not drop its own focus to the top of the page.
            Button(kind = ButtonKind.Ghost, size = ButtonSize.Sm, onClick = onUndo, pressable = !row.undoing) {
                Text(if (row.undoing) "Undoing…" else "Undo last match")
            }
        }
    }
    ChangesDialog(open = row.showingChanges, receipt = row.receipt, onClose = onCloseWhatChanged)
}

/** "Details matched just now", "Details matched 3 days ago", or with "… by Sam" when someone else matched it. */
internal fun lastMatchSentence(
    lastMatch: LastMatchUi,
    nowMs: Long,
): String {
    val sentence = "Details matched ${relativeLastActiveInSentence(lastMatch.appliedAtMs, nowMs)}"
    return lastMatch.matchedBy?.let { "$sentence by $it" } ?: sentence
}

/** How the receipt's region says an Undo outcome from the row. */
internal fun LastMatchEvent.toReceiptState(): MatchReceiptUiState =
    when (this) {
        LastMatchEvent.Undone -> MatchReceiptUiState.Undone
        LastMatchEvent.Expired -> MatchReceiptUiState.Expired
    }
