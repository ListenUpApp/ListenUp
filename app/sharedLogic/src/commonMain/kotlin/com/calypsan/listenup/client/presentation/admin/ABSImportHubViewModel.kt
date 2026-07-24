package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.ImportRepository
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * UI state for the read-only ABS import list shown inline in `AdminBackupScreen`.
 *
 * Sealed hierarchy:
 * - [Loading] before the first `listImports()` emission.
 * - [Ready] once the list has loaded; carries the staged imports and a transient [error] for
 *   mutation/refresh failures surfaced as a snackbar (alongside the global error bus).
 * - [Error] terminal state when the initial load fails before the list rendered.
 *
 * The new linear ABS import pipeline lives entirely in `ImportFlow`; this surface only lists past
 * imports and deletes them. Per-import detail editing (the old USERS/BOOKS/SESSIONS hub) is gone.
 */
sealed interface ABSImportListUiState {
    data object Loading : ABSImportListUiState

    /** List loaded; [error] surfaces a transient refresh/delete failure without clearing the list. */
    data class Ready(
        val imports: List<ImportSummary> = emptyList(),
        val error: AppError? = null,
    ) : ABSImportListUiState

    /** Initial load failed before the list rendered; terminal state. */
    data class Error(
        val error: AppError,
    ) : ABSImportListUiState
}

/**
 * ViewModel for the inline ABS import list in `AdminBackupScreen`.
 *
 * Backed by [ImportRepository] (the new `ImportService` RPC + REST upload stack) — the same
 * repository the linear `ImportFlow` uses. It lists staged imports and deletes them; creating and
 * running an import is owned by `ImportFlow`, reached via the screen's "new import" action.
 */
class ABSImportHubViewModel(
    private val importRepository: ImportRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val listState: StateFlow<ABSImportListUiState>
        field = MutableStateFlow<ABSImportListUiState>(ABSImportListUiState.Loading)

    init {
        refresh()
    }

    /** (Re)loads the staged-import list. Failures keep an already-rendered list and surface a snackbar. */
    fun refresh() {
        viewModelScope.launch {
            when (val result = importRepository.listImports()) {
                is AppResult.Success -> {
                    listState.update { current ->
                        if (current is ABSImportListUiState.Ready) {
                            current.copy(imports = result.data, error = null)
                        } else {
                            ABSImportListUiState.Ready(imports = result.data)
                        }
                    }
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to load imports: ${result.error.debugInfo ?: result.error.code}" }
                    errorBus.emit(result.error)
                    listState.update { current ->
                        if (current is ABSImportListUiState.Ready) {
                            current.copy(error = result.error)
                        } else {
                            ABSImportListUiState.Error(result.error)
                        }
                    }
                }
            }
        }
    }

    /** Deletes a staged import, then refreshes the list. */
    fun deleteImport(importId: ImportId) {
        viewModelScope.launch {
            when (val result = importRepository.deleteImport(importId)) {
                is AppResult.Success -> {
                    refresh()
                }

                is AppResult.Failure -> {
                    logger.error { "Failed to delete import: ${result.error.debugInfo ?: result.error.code}" }
                    errorBus.emit(result.error)
                    listState.update { current ->
                        if (current is ABSImportListUiState.Ready) current.copy(error = result.error) else current
                    }
                }
            }
        }
    }

    /** Clears the transient list error (e.g. after the snackbar is shown). */
    fun clearError() {
        listState.update { current ->
            if (current is ABSImportListUiState.Ready) current.copy(error = null) else current
        }
    }
}
