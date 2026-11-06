package com.calypsan.listenup.web.features.bookedit

import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedBookEdit(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit = {},
    onOpen: (String) -> Unit = {},
): OpenBookEdit =
    { bookId ->
        onOpen(bookId)
        BookEditSession(
            state = MutableStateFlow(state),
            navActions = emptyFlow(),
            onEvent = onEvent,
            close = {},
        )
    }
