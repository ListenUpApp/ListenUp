package com.calypsan.listenup.web.features.match

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.MatchReceiptUiState
import com.calypsan.listenup.client.presentation.match.MatchReceiptViewModel
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/** The Match details receipt for one book or person: its state, Undo, and dismissing it. */
class MatchReceiptSession(
    val state: StateFlow<MatchReceiptUiState>,
    val undo: () -> Unit,
    val dismiss: () -> Unit,
    val close: () -> Unit,
)

/** How Book Detail or the contributor page gets the receipt session for its book or person. */
typealias OpenMatchReceipt = (subjectId: String) -> MatchReceiptSession

/** The production source: [MatchReceiptViewModel] for one book or contributor. */
fun graphMatchReceipt(koin: Koin): OpenMatchReceipt =
    { subjectId ->
        val receipt = koin.get<MatchReceiptViewModel> { parametersOf(subjectId) }
        val store = ViewModelStore().apply { put(subjectId, receipt) }
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
 * A whole Match details graph for specs: a book's session from [match] (Find still searching, by default), a
 * person's from [person], and the receipt from [receipt]. [opened] hears which book or contributor each page
 * session was for.
 */
fun fixedMatchDetails(
    match: () -> BookMatchSession = {
        fixedBookMatch(MutableStateFlow(FindUiState.Searching(yourCopy = null, query = "", previous = null)))
    },
    person: () -> PersonMatchSession = {
        fixedPersonMatch(
            MutableStateFlow(
                PersonFindUiState.Searching(
                    role = ContributorRole.AUTHOR,
                    header = null,
                    inLibrary = null,
                    query = "",
                    previous = null,
                ),
            ),
        )
    },
    receipt: OpenMatchReceipt = fixedMatchReceipt(),
    opened: (String) -> Unit = {},
): MatchDetailsGraph =
    MatchDetailsGraph(
        openBookMatch = { bookId ->
            opened(bookId)
            match()
        },
        openPersonMatch = { contributorId ->
            opened(contributorId)
            person()
        },
        openMatchReceipt = receipt,
    )
