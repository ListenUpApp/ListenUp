package com.calypsan.listenup.web.features.setup

import com.calypsan.listenup.client.presentation.setup.LibrarySetupNavAction
import com.calypsan.listenup.client.presentation.setup.LibrarySetupUiState
import com.calypsan.listenup.client.presentation.startup.LibraryReadiness
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    onRetry: () -> Unit = {},
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
            onRetry = onRetry,
            close = {},
        )
    }

/** A readiness that never changes unless the spec moves [readiness] itself. */
fun fixedLibraryReadiness(
    readiness: StateFlow<LibraryReadiness> = MutableStateFlow(LibraryReadiness.Ready),
    onRetry: () -> Unit = {},
    onSetupComplete: () -> Unit = {},
    onContinueToPartialLibrary: () -> Unit = {},
): OpenLibraryReadiness =
    {
        LibraryReadinessSession(
            readiness = readiness,
            onRetry = onRetry,
            onSetupComplete = onSetupComplete,
            onContinueToPartialLibrary = onContinueToPartialLibrary,
            close = {},
        )
    }
