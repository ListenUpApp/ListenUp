package com.calypsan.listenup.web.features.library

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.library.LibraryUiEvent
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.client.presentation.library.LibraryViewModel
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open Library state stream, the events it accepts, and the teardown for it.
 *
 * A browser has no `ViewModelStore` to hand a ViewModel's lifetime to, so the page owns it — the
 * same arrangement [com.calypsan.listenup.web.features.bookdetail.BookDetailSession] makes, for the
 * same reason: without it, every visit would leave its flows collecting for the life of the tab.
 */
class LibrarySession(
    val state: StateFlow<LibraryUiState>,
    val onEvent: (LibraryUiEvent) -> Unit,
    val close: () -> Unit,
)

/**
 * How the page gets its state. Production resolves the real ViewModel out of the client graph
 * ([graphLibrary]); specs hand over a fixed state instead, so layout and sort contracts can be
 * driven without a database behind them.
 */
typealias OpenLibrary = () -> LibrarySession

/**
 * The production source: the shared [LibraryViewModel], resolved from the started Koin graph.
 *
 * `onScreenVisible()` is called on open because the shared ViewModel reloads sort preferences there.
 * A browser has no `onResume` to hang that on, and without it the page renders with default sort
 * until something else happens to nudge it.
 */
fun graphLibrary(koin: Koin): OpenLibrary =
    {
        val viewModel = koin.get<LibraryViewModel>()
        val store = ViewModelStore().apply { put("library", viewModel) }
        viewModel.onScreenVisible()
        LibrarySession(
            state = viewModel.uiState,
            onEvent = viewModel::onEvent,
            close = store::clear,
        )
    }
