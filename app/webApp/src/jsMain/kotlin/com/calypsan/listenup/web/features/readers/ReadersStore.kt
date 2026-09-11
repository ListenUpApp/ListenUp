package com.calypsan.listenup.web.features.readers

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/** An open readers session — everyone reading, or who has finished, one book. */
class BookReadersSession(
    val state: StateFlow<BookReadersUiState>,
    val close: () -> Unit,
)

/** How the readers surfaces get their state. Production resolves the shared ViewModel. */
typealias OpenBookReaders = (bookId: String) -> BookReadersSession

/**
 * The production source: the shared [BookReadersViewModel], parametrized on the book.
 *
 * ⛔ The book id is a *constructor* parameter here, not a `load()` call — this ViewModel takes it
 * through Koin's `parametersOf`, unlike every other session in this app. A store that resolved it
 * bare would get whichever book the graph happened to hand back.
 */
fun graphBookReaders(koin: Koin): OpenBookReaders =
    { bookId ->
        val viewModel = koin.get<BookReadersViewModel> { parametersOf(bookId) }
        val store = ViewModelStore().apply { put(bookId, viewModel) }
        BookReadersSession(state = viewModel.uiState, close = store::clear)
    }

/** A session over a state that never changes — the shape specs pass in place of the graph. */
fun fixedBookReaders(
    state: BookReadersUiState,
    onOpen: (String) -> Unit = {},
): OpenBookReaders =
    { bookId ->
        onOpen(bookId)
        BookReadersSession(state = MutableStateFlow(state), close = {})
    }
