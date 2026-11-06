package com.calypsan.listenup.web.features.readers

import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import kotlinx.coroutines.flow.MutableStateFlow

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedBookReaders(
    state: BookReadersUiState,
    onOpen: (String) -> Unit = {},
): OpenBookReaders =
    { bookId ->
        onOpen(bookId)
        BookReadersSession(state = MutableStateFlow(state), close = {})
    }
