package com.calypsan.listenup.web.features.match

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.MatchReceiptUiState
import com.calypsan.listenup.client.presentation.match.MatchReceiptViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/** Book Detail's Match details receipt for one book: its state, Undo, and dismissing it. */
class MatchReceiptSession(
    val state: StateFlow<MatchReceiptUiState>,
    val undo: () -> Unit,
    val dismiss: () -> Unit,
    val close: () -> Unit,
)

/** How Book Detail gets the receipt session for a book. */
typealias OpenMatchReceipt = (bookId: String) -> MatchReceiptSession

/** The production source: [MatchReceiptViewModel] for one book. */
fun graphMatchReceipt(koin: Koin): OpenMatchReceipt =
    { bookId ->
        val receipt = koin.get<MatchReceiptViewModel> { parametersOf(bookId) }
        val store = ViewModelStore().apply { put(bookId, receipt) }
        MatchReceiptSession(
            state = receipt.state,
            undo = receipt::undo,
            dismiss = receipt::dismiss,
            close = store::clear,
        )
    }

/** A receipt session over a flow a spec owns, recording Undo and dismiss. */
fun fixedMatchReceipt(
    state: StateFlow<MatchReceiptUiState> = MutableStateFlow(MatchReceiptUiState.None),
    undo: () -> Unit = {},
    dismiss: () -> Unit = {},
): OpenMatchReceipt = { _ -> MatchReceiptSession(state = state, undo = undo, dismiss = dismiss, close = {}) }

/**
 * A whole Match details graph for specs: the page's session from [match] (Find still searching, by
 * default), and Book Detail's receipt from [receipt]. [opened] hears which book each session was for.
 */
fun fixedMatchDetails(
    match: () -> BookMatchSession = {
        fixedBookMatch(MutableStateFlow(FindUiState.Searching(yourCopy = null, query = "", previous = null)))
    },
    receipt: OpenMatchReceipt = fixedMatchReceipt(),
    opened: (String) -> Unit = {},
): MatchDetailsGraph =
    MatchDetailsGraph(
        openBookMatch = { bookId ->
            opened(bookId)
            match()
        },
        openMatchReceipt = receipt,
    )
