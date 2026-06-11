package com.calypsan.listenup.client.presentation.admin.absimport

import com.calypsan.listenup.client.presentation.admin.ABSImportUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Mutate the state only while it is [ABSImportUiState.Ready] — the one mutation rule, shared by the VM + delegates. */
internal fun MutableStateFlow<ABSImportUiState>.updateReady(
    transform: (ABSImportUiState.Ready) -> ABSImportUiState.Ready,
) {
    update { current -> if (current is ABSImportUiState.Ready) transform(current) else current }
}

/** Current state as [ABSImportUiState.Ready], or null if in another phase. */
internal val MutableStateFlow<ABSImportUiState>.ready: ABSImportUiState.Ready?
    get() = value as? ABSImportUiState.Ready
