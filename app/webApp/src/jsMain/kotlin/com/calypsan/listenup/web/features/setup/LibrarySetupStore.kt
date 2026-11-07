package com.calypsan.listenup.web.features.setup

import com.calypsan.listenup.web.nav.RouteSession
import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.setup.LibrarySetupNavAction
import com.calypsan.listenup.client.presentation.setup.LibrarySetupUiState
import com.calypsan.listenup.client.presentation.setup.LibrarySetupViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.Koin

/**
 * An open library-setup wizard, the gestures it accepts, and the teardown for it.
 *
 * [navActions] carries the single one-shot the wizard emits — `Finished`. It is a flow rather than
 * a state field because it is an event: the wizard stays on its picker when setup completes, so the
 * host has to hear "done" once and stop showing the wizard itself.
 */
class LibrarySetupSession(
    val state: StateFlow<LibrarySetupUiState>,
    val navActions: Flow<LibrarySetupNavAction>,
    val onOpenFolder: (String) -> Unit,
    val onNavigateUp: () -> Unit,
    val onToggleFolder: (String) -> Unit,
    val onComplete: () -> Unit,
    val onDismissError: () -> Unit,
    /** Choose a folder as a whole — the page passes the one currently being browsed. */
    val onSelectFolder: (String) -> Unit,
    val onClearSelection: () -> Unit,
    /** Ask the server again whether setup is needed — the retry for a check that failed. */
    val onRetry: () -> Unit,
    override val close: () -> Unit,
) : RouteSession

/** How the gate gets the wizard. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenLibrarySetup = () -> LibrarySetupSession

/**
 * The production wizard: the shared [LibrarySetupViewModel].
 *
 * It checks the server's setup status in its own `init`, so resolving it IS the first probe —
 * there is no separate "start" call to forget.
 */
fun graphLibrarySetup(koin: Koin): OpenLibrarySetup =
    {
        val viewModel = koin.get<LibrarySetupViewModel>()
        val store = ViewModelStore().apply { put("library-setup", viewModel) }
        LibrarySetupSession(
            state = viewModel.state,
            navActions = viewModel.navActions,
            onOpenFolder = viewModel::loadDirectory,
            onNavigateUp = viewModel::navigateUp,
            onToggleFolder = viewModel::togglePath,
            onComplete = viewModel::completeSetup,
            onDismissError = viewModel::clearError,
            onSelectFolder = viewModel::selectPath,
            onClearSelection = viewModel::clearSelection,
            onRetry = viewModel::retryStatusCheck,
            close = store::clear,
        )
    }
