package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.match.MatchReceiptUiState
import com.calypsan.listenup.web.features.match.LastMatchRow
import com.calypsan.listenup.web.features.match.toReceiptState

/**
 * [session]'s last-match row, and how its Undo ended: the outcome replaces the row until it is dismissed or the
 * reader leaves the book.
 */
@Composable
fun BookLastMatch(
    session: BookDetailSession,
    nowMs: Long,
) {
    var outcome by remember(session) { mutableStateOf<MatchReceiptUiState>(MatchReceiptUiState.None) }
    LaunchedEffect(session) {
        session.lastMatchEvents.collect { event -> outcome = event.toReceiptState() }
    }
    LastMatchRow(
        lastMatch = session.lastMatch.collectAsState().value,
        outcome = outcome,
        nowMs = nowMs,
        onSeeWhatChanged = session.onSeeWhatChanged,
        onCloseWhatChanged = session.onCloseWhatChanged,
        onUndo = session.onUndoLastMatch,
        onDismissOutcome = { outcome = MatchReceiptUiState.None },
    )
}
