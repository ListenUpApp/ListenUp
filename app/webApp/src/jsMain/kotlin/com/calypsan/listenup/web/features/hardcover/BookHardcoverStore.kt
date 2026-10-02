package com.calypsan.listenup.web.features.hardcover

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverUiState
import com.calypsan.listenup.client.presentation.hardcover.BookHardcoverViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/**
 * Book Detail's Hardcover panel for one book: its state, Remove match, Sync with Hardcover (#1541), and the
 * teardown. Change match and Find on Hardcover are navigations the page owns, so they are not here.
 */
class BookHardcoverSession(
    val state: StateFlow<BookHardcoverUiState>,
    val onRemoveMatch: () -> Unit,
    val onSetSynced: (Boolean) -> Unit,
    val close: () -> Unit,
)

/** How Book Detail gets its Hardcover panel. */
typealias OpenBookHardcover = (bookId: String) -> BookHardcoverSession

/** The production source: the shared [BookHardcoverViewModel], parametrized on the book. */
fun graphBookHardcover(koin: Koin): OpenBookHardcover =
    { bookId ->
        val viewModel = koin.get<BookHardcoverViewModel> { parametersOf(bookId) }
        val store = ViewModelStore().apply { put(bookId, viewModel) }
        BookHardcoverSession(
            state = viewModel.uiState,
            onRemoveMatch = viewModel::removeMatch,
            onSetSynced = viewModel::setSynced,
            close = store::clear,
        )
    }

/** A session over a state that never changes, recording Remove match and the switch — for specs. */
fun fixedBookHardcover(
    state: BookHardcoverUiState = BookHardcoverUiState.Hidden,
    onRemoveMatch: () -> Unit = {},
    onSetSynced: (Boolean) -> Unit = {},
): OpenBookHardcover =
    {
        BookHardcoverSession(
            MutableStateFlow(state),
            onRemoveMatch = onRemoveMatch,
            onSetSynced = onSetSynced,
            close = {},
        )
    }
