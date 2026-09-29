package com.calypsan.listenup.web.features.ratings

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/**
 * An open ratings session for one book: its state, and the things a listener (or an admin) can do
 * to it.
 *
 * ⛔ The actions are part of the seam, not an afterthought. Web's history is sessions that carried
 * `(state, close)` and nothing else — a page that could show a rating and never take one.
 */
class BookRatingsSession(
    val state: StateFlow<BookRatingsUiState>,
    val rate: (halfStars: Int, note: String?) -> Unit,
    val clear: () -> Unit,
    val refreshExternal: () -> Unit,
    val close: () -> Unit,
)

/** How Book Detail gets its rating session. Production resolves the shared ViewModel. */
typealias OpenBookRatings = (bookId: String) -> BookRatingsSession

/**
 * The production source: the shared [BookRatingsViewModel], parametrized on the book the same way
 * `graphBookReaders` is — the id is a constructor parameter, so it goes through `parametersOf`.
 */
fun graphBookRatings(koin: Koin): OpenBookRatings =
    { bookId ->
        val viewModel = koin.get<BookRatingsViewModel> { parametersOf(bookId) }
        val store = ViewModelStore().apply { put(bookId, viewModel) }
        BookRatingsSession(
            state = viewModel.state,
            rate = viewModel::rate,
            clear = viewModel::clear,
            refreshExternal = viewModel::refreshExternal,
            close = store::clear,
        )
    }

/** A session over a state that never changes, recording what it is asked to do — for specs. */
fun fixedBookRatings(
    state: BookRatingsUiState,
    onRate: (halfStars: Int, note: String?) -> Unit = { _, _ -> },
    onClear: () -> Unit = {},
    onRefreshExternal: () -> Unit = {},
): OpenBookRatings =
    {
        BookRatingsSession(
            state = MutableStateFlow(state),
            rate = onRate,
            clear = onClear,
            refreshExternal = onRefreshExternal,
            close = {},
        )
    }
