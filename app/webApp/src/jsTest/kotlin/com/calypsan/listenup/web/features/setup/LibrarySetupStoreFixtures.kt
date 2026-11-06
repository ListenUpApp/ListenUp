package com.calypsan.listenup.web.features.setup

import com.calypsan.listenup.client.presentation.setup.LibrarySetupNavAction
import com.calypsan.listenup.client.presentation.setup.LibrarySetupUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

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
