package com.calypsan.listenup.web.features.admin

import com.calypsan.listenup.client.presentation.admin.LibrarySettingsEvent
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

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
