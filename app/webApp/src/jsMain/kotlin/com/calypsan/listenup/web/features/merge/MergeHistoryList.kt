package com.calypsan.listenup.web.features.merge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.client.presentation.merge.MergeHistoryState
import com.calypsan.listenup.client.presentation.merge.MergeUndoOutcome
import com.calypsan.listenup.client.util.formatDateLong
import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.web.design.ConfirmDialog
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

private const val ATTR_TYPE = "type"
private const val VALUE_BUTTON = "button"
private const val HINT = "mh-hint"

/**
 * The "Merged into this" list (#1061): every merge folded into this series or genre that can still
 * be undone, each with Undo. The series editor shows it as a section; the categories admin shows it
 * in a genre's merge-history dialog. One component, so both say the same thing the same way.
 *
 * Undo is confirmed first — it brings the merged-away series or genre back and moves books — and
 * the result is said in words where the reader is already looking, not in a toast that fades.
 */
@Composable
fun MergeHistoryList(
    state: MergeHistoryState,
    onUndo: (MergeReceiptId) -> Unit,
    onRetry: () -> Unit,
) {
    var confirming by remember { mutableStateOf<MergeReceipt?>(null) }

    Div(attrs = { classes("mh") }) {
        when (state) {
            MergeHistoryState.Loading -> {
                Div(attrs = { classes("skel", "mh-skel") })
            }

            is MergeHistoryState.Unavailable -> {
                P(attrs = {
                    classes(HINT)
                    attr("role", "alert")
                }) { Text(state.error.message) }
                Button(attrs = {
                    classes("btn-o", "mh-retry")
                    attr(ATTR_TYPE, VALUE_BUTTON)
                    onClick { onRetry() }
                }) { Text("Try again") }
            }

            is MergeHistoryState.Ready -> {
                state.outcome?.let { Outcome(it) }
                if (state.receipts.isEmpty()) {
                    P(attrs = { classes(HINT) }) { Text("Nothing has been merged into this.") }
                }
                state.receipts.forEach { receipt ->
                    ReceiptRow(
                        receipt = receipt,
                        isUndoing = receipt.id == state.undoingId,
                        // One undo at a time: the others wait rather than queue behind it.
                        canUndo = state.undoingId == null,
                        onUndo = { confirming = receipt },
                    )
                }
                P(attrs = { classes(HINT) }) { Text("Merges made before this version can't be undone.") }
            }
        }
    }

    confirming?.let { receipt ->
        ConfirmDialog(
            open = true,
            title = "Undo this merge?",
            body =
                "“${receipt.sourceName}” comes back, and its books move back to it. " +
                    "Books changed since the merge stay where they are.",
            confirmLabel = "Undo merge",
            onConfirm = {
                confirming = null
                onUndo(receipt.id)
            },
            onDismiss = { confirming = null },
        )
    }
}

@Composable
private fun ReceiptRow(
    receipt: MergeReceipt,
    isUndoing: Boolean,
    canUndo: Boolean,
    onUndo: () -> Unit,
) {
    Div(attrs = { classes("mh-row") }) {
        Div(attrs = { classes("mh-main") }) {
            Span(attrs = { classes("mh-name") }) { Text(receipt.sourceName) }
            Span(attrs = { classes("mh-detail") }) { Text(receiptDetail(receipt)) }
        }
        Button(attrs = {
            classes("btn-o", "mh-undo")
            attr(ATTR_TYPE, VALUE_BUTTON)
            if (!canUndo) attr("disabled", "")
            onClick { onUndo() }
        }) { Text(if (isUndoing) "Undoing…" else "Undo") }
    }
}

/** "Up to 4 books · September 25, 2026 · by Simon" — the name only while the account exists. */
private fun receiptDetail(receipt: MergeReceipt): String {
    val books = if (receipt.bookCount == 1) "Up to 1 book" else "Up to ${receipt.bookCount} books"
    return listOfNotNull(books, formatDateLong(receipt.mergedAt), receipt.mergedByName?.let { "by $it" })
        .joinToString(" · ")
}

@Composable
private fun Outcome(outcome: MergeUndoOutcome) {
    Div(attrs = {
        classes("mh-outcome")
        attr("role", "status")
    }) {
        val moved =
            if (outcome.booksRestored ==
                1
            ) {
                "1 book moved back."
            } else {
                "${outcome.booksRestored} books moved back."
            }
        P(attrs = { classes("mh-outcome-t") }) { Text("“${outcome.sourceName}” is back. $moved") }
        if (outcome.booksSkipped > 0) {
            val skipped =
                if (outcome.booksSkipped == 1) {
                    "1 book had changed since and stayed where it was."
                } else {
                    "${outcome.booksSkipped} books had changed since and stayed where they were."
                }
            P(attrs = { classes(HINT) }) { Text(skipped) }
        }
        if (outcome.restoredAtTopLevel) {
            P(attrs = { classes(HINT) }) { Text("Its old parent is gone, so it's at the top level now.") }
        }
    }
}
