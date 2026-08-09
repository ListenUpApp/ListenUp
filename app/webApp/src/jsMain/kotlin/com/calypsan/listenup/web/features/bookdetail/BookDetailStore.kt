package com.calypsan.listenup.web.features.bookdetail

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open Book Detail state stream, plus the teardown for it.
 *
 * A browser has no `ViewModelStore` to hand a ViewModel's lifetime to, so the page owns it: the
 * composition opens a session when it starts showing a book and closes it when it stops. Without
 * that, every book a reader visited would leave its flows collecting for the life of the tab.
 */
class BookDetailSession(
    val state: StateFlow<BookDetailUiState>,
    val close: () -> Unit,
)

/**
 * How the page gets its state. Production resolves the real ViewModel out of the client graph
 * ([graphBookDetail]); specs hand over a fixed state instead, so the URL, layout and selection
 * contracts can be driven without a database behind them.
 */
typealias OpenBookDetail = (bookId: String) -> BookDetailSession

/**
 * The production source: the shared [BookDetailViewModel], resolved from the started Koin graph
 * and pointed at [bookId].
 *
 * The ViewModel goes into a [ViewModelStore] of its own, because that is the only thing allowed
 * to end one — `ViewModel.clear()`, which cancels `viewModelScope` and with it every flow the
 * ViewModel exposes, is internal to the lifecycle library. Clearing the store is the same call a
 * native client's screen makes when it goes away; here the store's scope is one visited book.
 */
fun graphBookDetail(koin: Koin): OpenBookDetail =
    { bookId ->
        val viewModel = koin.get<BookDetailViewModel>()
        val store = ViewModelStore().apply { put(bookId, viewModel) }
        viewModel.loadBook(bookId)
        BookDetailSession(state = viewModel.state, close = store::clear)
    }

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedBookDetail(state: BookDetailUiState): OpenBookDetail =
    { BookDetailSession(state = MutableStateFlow(state), close = {}) }
