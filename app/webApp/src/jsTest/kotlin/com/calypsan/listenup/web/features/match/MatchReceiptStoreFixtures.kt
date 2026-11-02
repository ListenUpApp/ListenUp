package com.calypsan.listenup.web.features.match

import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.MatchReceiptUiState
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
