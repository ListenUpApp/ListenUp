package com.calypsan.listenup.web.features.setup

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.setup.LibrarySetupNavAction
import com.calypsan.listenup.client.presentation.setup.LibrarySetupUiState
import com.calypsan.listenup.client.presentation.setup.LibrarySetupViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin

/**
 * An open library-setup wizard, the gestures it accepts, and the teardown for it.
 *
 * [navActions] carries the single one-shot the wizard emits — `Finished`. It is a flow rather than
 * a state field because it is an event: the ViewModel does not flip `needsSetup` back to false when
 * setup completes, so the host has to hear "done" once and stop showing the wizard itself.
 */
class LibrarySetupSession(
    val state: StateFlow<LibrarySetupUiState>,
    val navActions: Flow<LibrarySetupNavAction>,
    val onOpenFolder: (String) -> Unit,
    val onNavigateUp: () -> Unit,
    val onToggleFolder: (String) -> Unit,
    val onComplete: () -> Unit,
    val onDismissError: () -> Unit,
    val close: () -> Unit,
)

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
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs use in place of the graph. */
fun fixedLibrarySetup(
    state: LibrarySetupUiState,
    navActions: Flow<LibrarySetupNavAction> = emptyFlow(),
    onOpenFolder: (String) -> Unit = {},
    onNavigateUp: () -> Unit = {},
    onToggleFolder: (String) -> Unit = {},
    onComplete: () -> Unit = {},
    onDismissError: () -> Unit = {},
): OpenLibrarySetup =
    {
        LibrarySetupSession(
            state = MutableStateFlow(state),
            navActions = navActions,
            onOpenFolder = onOpenFolder,
            onNavigateUp = onNavigateUp,
            onToggleFolder = onToggleFolder,
            onComplete = onComplete,
            onDismissError = onDismissError,
            close = {},
        )
    }
