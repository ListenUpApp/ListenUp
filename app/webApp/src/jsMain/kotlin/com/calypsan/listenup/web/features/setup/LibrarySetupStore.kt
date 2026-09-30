package com.calypsan.listenup.web.features.setup

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.api.result.getOrDefault
import com.calypsan.listenup.client.core.suspendRunCatching
import com.calypsan.listenup.client.domain.repository.SyncRepository
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
    /** Choose a folder as a whole — the page passes the one currently being browsed. */
    val onSelectFolder: (String) -> Unit,
    val onClearSelection: () -> Unit,
    /** Ask the server again whether setup is needed — the retry for a probe that failed. */
    val onCheckStatus: () -> Unit,
    /**
     * Whether this browser already holds a library. Consulted only when the setup probe fails:
     * a reader with a mirror opens offline rather than being walled out by a server that is down.
     */
    val hasLocalLibrary: suspend () -> Boolean,
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
        val syncRepository = koin.get<SyncRepository>()
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
            onCheckStatus = viewModel::checkLibraryStatus,
            // `getOrDefault(false)` on failure: an unreadable mirror is treated as no mirror, which
            // offers the retry — the one answer that cannot strand anyone.
            hasLocalLibrary = { suspendRunCatching { syncRepository.hasLocalLibrary() }.getOrDefault { false } },
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
    onSelectFolder: (String) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onCheckStatus: () -> Unit = {},
    hasLocalLibrary: suspend () -> Boolean = { false },
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
            onSelectFolder = onSelectFolder,
            onClearSelection = onClearSelection,
            onCheckStatus = onCheckStatus,
            hasLocalLibrary = hasLocalLibrary,
            close = {},
        )
    }
