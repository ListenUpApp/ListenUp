package com.calypsan.listenup.web.features.bookedit

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.bookedit.BookEditNavAction
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiEvent
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.bookedit.BookEditViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin

/**
 * An open Book Edit session: the state to render, the events to send back, the navigation the
 * ViewModel asks for, and the teardown.
 *
 * Same shape and same reason as `BookDetailSession` — a browser has no `ViewModelStore` to hand a
 * ViewModel's lifetime to, so the page owns it and closes it on the way out. An edit session holds
 * more than a detail one (a form's worth of pending state), which makes leaking it worse rather
 * than better.
 */
class BookEditSession(
    val state: StateFlow<BookEditUiState>,
    val navActions: Flow<BookEditNavAction>,
    val onEvent: (BookEditUiEvent) -> Unit,
    val close: () -> Unit,
)

/**
 * How the edit page gets its state. Production resolves the shared ViewModel out of the client
 * graph ([graphBookEdit]); specs hand over a fixed state, so the form's rendering and event
 * contracts can be driven without a database behind them.
 */
typealias OpenBookEdit = (bookId: String) -> BookEditSession

/**
 * The production source: the shared [BookEditViewModel], resolved from the started Koin graph and
 * pointed at [bookId].
 *
 * The ViewModel goes into a [ViewModelStore] of its own for the same reason Book Detail's does —
 * `ViewModel.clear()` is the only thing that ends one, and it is internal to the lifecycle
 * library. Clearing the store cancels `viewModelScope`, and with it the form's pending edits.
 */
fun graphBookEdit(koin: Koin): OpenBookEdit =
    { bookId ->
        val viewModel = koin.get<BookEditViewModel>()
        val store = ViewModelStore().apply { put(bookId, viewModel) }
        viewModel.loadBook(bookId)
        BookEditSession(
            state = viewModel.state,
            navActions = viewModel.navActions,
            onEvent = viewModel::onEvent,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedBookEdit(
    state: BookEditUiState,
    onEvent: (BookEditUiEvent) -> Unit = {},
): OpenBookEdit =
    {
        BookEditSession(
            state = MutableStateFlow(state),
            navActions = emptyFlow(),
            onEvent = onEvent,
            close = {},
        )
    }
