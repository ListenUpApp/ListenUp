package com.calypsan.listenup.web.features.hardcover

import com.calypsan.listenup.web.nav.RouteSession
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchEvent
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchUiState
import com.calypsan.listenup.client.presentation.hardcover.HardcoverMatchViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin
import org.koin.core.parameter.parametersOf

/**
 * An open Find on Hardcover session for one book: its state, its one-shot effects, every gesture the
 * page can make — type, search, search a suggestion, pick, remove the match — and the Undo a pick's
 * toast offers.
 *
 * [onUndoLink] outlives the page on purpose. The toast that offers it is shown as the page closes,
 * so it is pressed after [close] has run; the ViewModel runs the undo on the app's scope, not its own.
 */
class HardcoverMatchSession(
    val state: StateFlow<HardcoverMatchUiState>,
    val events: Flow<HardcoverMatchEvent>,
    val onQueryChange: (String) -> Unit,
    val onSearch: () -> Unit,
    val onSearchFor: (String) -> Unit,
    val onPick: (hcBookId: Long) -> Unit,
    val onRemoveMatch: () -> Unit,
    val onUndoLink: () -> Unit,
    override val close: () -> Unit,
) : RouteSession

/** How the page gets its session for a book. */
typealias OpenHardcoverMatch = (bookId: String) -> HardcoverMatchSession

/** The production source: the shared [HardcoverMatchViewModel], parametrized on the book. */
fun graphHardcoverMatch(koin: Koin): OpenHardcoverMatch =
    { bookId ->
        val viewModel = koin.get<HardcoverMatchViewModel> { parametersOf(bookId) }
        val store = ViewModelStore().apply { put(bookId, viewModel) }
        HardcoverMatchSession(
            state = viewModel.uiState,
            events = viewModel.events,
            onQueryChange = viewModel::onQueryChange,
            onSearch = viewModel::search,
            onSearchFor = viewModel::searchFor,
            onPick = viewModel::link,
            onRemoveMatch = viewModel::removeMatch,
            onUndoLink = viewModel::undoLink,
            close = store::clear,
        )
    }
