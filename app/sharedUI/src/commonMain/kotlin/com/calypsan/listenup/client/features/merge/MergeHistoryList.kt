package com.calypsan.listenup.client.features.merge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.presentation.merge.MergeHistoryState
import com.calypsan.listenup.client.presentation.merge.MergeUndoOutcome
import com.calypsan.listenup.client.util.formatDateLong
import com.calypsan.listenup.core.MergeReceiptId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.merge_history_confirm_action
import listenup.composeapp.generated.resources.merge_history_confirm_body
import listenup.composeapp.generated.resources.merge_history_confirm_title
import listenup.composeapp.generated.resources.merge_history_empty
import listenup.composeapp.generated.resources.merge_history_older_note
import listenup.composeapp.generated.resources.merge_history_outcome
import listenup.composeapp.generated.resources.merge_history_outcome_plural
import listenup.composeapp.generated.resources.merge_history_retry
import listenup.composeapp.generated.resources.merge_history_row_books
import listenup.composeapp.generated.resources.merge_history_row_books_plural
import listenup.composeapp.generated.resources.merge_history_row_by
import listenup.composeapp.generated.resources.merge_history_skipped
import listenup.composeapp.generated.resources.merge_history_skipped_plural
import listenup.composeapp.generated.resources.merge_history_top_level
import listenup.composeapp.generated.resources.merge_history_undo
import listenup.composeapp.generated.resources.merge_history_undoing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The "Merged into this" list (#1061): every merge folded into this series or genre that can still
 * be undone, each with Undo. Used by the series editor and the genre admin's merge-history sheet;
 * the host supplies the card or sheet around it.
 *
 * Undo is asked for first — it brings a series or genre back and moves books — and afterwards the
 * list says what came back and what was left alone, in words, where the reader is already looking.
 */
@Composable
fun MergeHistoryList(
    state: MergeHistoryState,
    onUndo: (MergeReceiptId) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf<MergeReceipt?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            MergeHistoryState.Loading -> {
                ListenUpLoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
            }

            is MergeHistoryState.Unavailable -> {
                Text(state.error.message, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRetry) { Text(stringResource(Res.string.merge_history_retry)) }
            }

            is MergeHistoryState.Ready -> {
                state.outcome?.let { UndoOutcome(it) }
                if (state.receipts.isEmpty()) {
                    Text(
                        stringResource(Res.string.merge_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                Text(
                    stringResource(Res.string.merge_history_older_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    confirming?.let { receipt ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(Res.string.merge_history_confirm_title)) },
            text = { Text(stringResource(Res.string.merge_history_confirm_body, receipt.sourceName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onUndo(receipt.id)
                }) { Text(stringResource(Res.string.merge_history_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(stringResource(Res.string.common_cancel)) }
            },
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(receipt.sourceName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                receiptDetail(receipt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isUndoing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.merge_history_undoing), style = MaterialTheme.typography.labelLarge)
            }
        } else {
            TextButton(onClick = onUndo, enabled = canUndo) { Text(stringResource(Res.string.merge_history_undo)) }
        }
    }
}

/** "Up to 4 books · September 25, 2026 · by Simon" — the name only while the account exists. */
@Composable
private fun receiptDetail(receipt: MergeReceipt): String {
    val books = plural(receipt.bookCount, Res.string.merge_history_row_books, Res.string.merge_history_row_books_plural)
    val by = receipt.mergedByName?.let { stringResource(Res.string.merge_history_row_by, it) }
    return listOfNotNull(books, formatDateLong(receipt.mergedAt), by).joinToString(" · ")
}

@Composable
private fun UndoOutcome(outcome: MergeUndoOutcome) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val back =
            if (outcome.booksRestored ==
                1
            ) {
                Res.string.merge_history_outcome
            } else {
                Res.string.merge_history_outcome_plural
            }
        Text(
            stringResource(back, outcome.sourceName, outcome.booksRestored),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (outcome.booksSkipped > 0) {
            Text(
                plural(outcome.booksSkipped, Res.string.merge_history_skipped, Res.string.merge_history_skipped_plural),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (outcome.restoredAtTopLevel) {
            Text(stringResource(Res.string.merge_history_top_level), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun plural(
    count: Int,
    one: StringResource,
    other: StringResource,
): String = stringResource(if (count == 1) one else other, count)
