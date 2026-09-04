package com.calypsan.listenup.web.features.admin

import androidx.lifecycle.ViewModelStore
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsEvent
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsUiState
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.core.Koin

/** An open Library Settings screen, the gestures it accepts, and the teardown for it. */
class LibrarySettingsSession(
    val state: StateFlow<LibrarySettingsUiState>,
    val events: Flow<LibrarySettingsEvent>,
    val onRemoveFolder: (String) -> Unit,
    val onAddPath: (String) -> Unit,
    val onScan: () -> Unit,
    val onShowBrowser: (Boolean) -> Unit,
    val onOpenBrowserPath: (String) -> Unit,
    val onBrowserUp: () -> Unit,
    val onClearError: () -> Unit,
    val close: () -> Unit,
)

/** How the page gets its state. Production resolves the real ViewModel; specs hand over a state. */
typealias OpenLibrarySettings = () -> LibrarySettingsSession

/** The production source: the shared [LibrarySettingsViewModel] over THE singleton library. */
fun graphLibrarySettings(koin: Koin): OpenLibrarySettings =
    {
        val viewModel = koin.get<LibrarySettingsViewModel>()
        val store = ViewModelStore().apply { put("library-settings", viewModel) }
        LibrarySettingsSession(
            state = viewModel.state,
            events = viewModel.events,
            onRemoveFolder = viewModel::removeFolder,
            onAddPath = viewModel::addScanPath,
            onScan = viewModel::triggerScan,
            onShowBrowser = viewModel::setShowFolderBrowser,
            onOpenBrowserPath = viewModel::loadBrowserDirectory,
            onBrowserUp = viewModel::browserNavigateUp,
            onClearError = viewModel::clearError,
            close = store::clear,
        )
    }

/** A session over a state that never changes — the shape specs use in place of the graph. */
@Suppress("LongParameterList")
fun fixedLibrarySettings(
    state: LibrarySettingsUiState,
    events: Flow<LibrarySettingsEvent> = emptyFlow(),
    onRemoveFolder: (String) -> Unit = {},
    onAddPath: (String) -> Unit = {},
    onScan: () -> Unit = {},
    onShowBrowser: (Boolean) -> Unit = {},
    onOpenBrowserPath: (String) -> Unit = {},
    onBrowserUp: () -> Unit = {},
    onClearError: () -> Unit = {},
): OpenLibrarySettings =
    {
        LibrarySettingsSession(
            state = MutableStateFlow(state),
            events = events,
            onRemoveFolder = onRemoveFolder,
            onAddPath = onAddPath,
            onScan = onScan,
            onShowBrowser = onShowBrowser,
            onOpenBrowserPath = onOpenBrowserPath,
            onBrowserUp = onBrowserUp,
            onClearError = onClearError,
            close = {},
        )
    }
