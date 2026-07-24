package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.BackupInfo
import com.calypsan.listenup.client.domain.model.toDomain
import com.calypsan.listenup.client.domain.repository.BackupRepository
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.io.RawSink

private val logger = KotlinLogging.logger {}

/**
 * UI state for the backup list screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first `listBackups()` emission.
 * - [Ready] once the backup list has loaded; carries backups, action overlays
 *   (`isCreating`, `isDeleting`), dialog state (`deleteConfirmBackup`), and a
 *   transient `error` for mutation failures surfaced as a snackbar.
 * - [Error] terminal state when the initial load (or a retry from [Error])
 *   fails. Refresh failures after we've reached [Ready] surface via the
 *   transient `error` field on [Ready] instead.
 */
sealed interface AdminBackupUiState {
    data object Loading : AdminBackupUiState

    /** Backups have loaded; carries the list, action overlays, dialog state, and a transient `error`. */
    data class Ready(
        val backups: List<BackupInfo> = emptyList(),
        val isCreating: Boolean = false,
        val isDeleting: Boolean = false,
        val error: AppError? = null,
        val deleteConfirmBackup: BackupInfo? = null,
    ) : AdminBackupUiState

    /** Terminal state when the initial backup-list load fails. */
    data class Error(
        val error: AppError,
    ) : AdminBackupUiState
}

/**
 * ViewModel for managing backups, backed by the [BackupService][com.calypsan.listenup.api.BackupService]
 * RPC via [BackupRepository].
 */
class AdminBackupViewModel(
    private val backupRepository: BackupRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<AdminBackupUiState>
        field = MutableStateFlow<AdminBackupUiState>(AdminBackupUiState.Loading)

    private val _downloadSaved = Channel<Unit>(Channel.BUFFERED)

    /** One-shot signal that a backup finished streaming to the device — drives a "saved" confirmation. */
    val downloadSaved: Flow<Unit> = _downloadSaved.receiveAsFlow()

    init {
        loadBackups()
    }

    fun loadBackups() {
        viewModelScope.launch {
            when (val result = backupRepository.listBackups()) {
                is AppResult.Success -> {
                    val sorted =
                        result.data
                            .map { summary -> summary.toDomain() }
                            .sortedByDescending { backup -> backup.createdAt }
                    state.update { current ->
                        if (current is AdminBackupUiState.Ready) {
                            current.copy(backups = sorted, error = null)
                        } else {
                            // First emission (from Loading) or recovering from Error:
                            // transition to Ready with fresh data and default UI fields.
                            AdminBackupUiState.Ready(backups = sorted)
                        }
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to load backups: ${result.error.message}" }
                    state.update { current ->
                        if (current is AdminBackupUiState.Ready) {
                            // Transient refresh failure once already loaded: keep
                            // backups and surface error to the snackbar.
                            current.copy(error = result.error)
                        } else {
                            // Initial load (or post-Error retry) failed: terminal Error state.
                            AdminBackupUiState.Error(result.error)
                        }
                    }
                }
            }
        }
    }

    fun createBackup(includeImages: Boolean) {
        viewModelScope.launch {
            updateReady { it.copy(isCreating = true, error = null) }

            when (val result = backupRepository.createBackup(includeImages = includeImages)) {
                is AppResult.Success -> {
                    // Reload list to show new backup
                    loadBackups()
                    updateReady { it.copy(isCreating = false) }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to create backup: ${result.error.message}" }
                    updateReady {
                        it.copy(
                            isCreating = false,
                            error = result.error,
                        )
                    }
                }
            }
        }
    }

    /**
     * Stream the backup identified by [id] into [sink] (a user-chosen file on the device), then close
     * [sink] so the underlying file flushes and finalizes — even if the write was cancelled. Emits
     * [downloadSaved] on success; routes failures to the global snackbar.
     */
    fun downloadBackup(
        id: BackupId,
        sink: RawSink,
    ) {
        viewModelScope.launch {
            val result =
                try {
                    backupRepository.downloadBackup(id, sink)
                } finally {
                    sink.close()
                }
            when (result) {
                is AppResult.Success -> {
                    _downloadSaved.trySend(Unit)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to download backup: ${result.error.message}" }
                }
            }
        }
    }

    fun showDeleteConfirmation(backup: BackupInfo) {
        updateReady { it.copy(deleteConfirmBackup = backup) }
    }

    fun dismissDeleteConfirmation() {
        updateReady { it.copy(deleteConfirmBackup = null) }
    }

    fun deleteBackup(backup: BackupInfo) {
        viewModelScope.launch {
            updateReady { it.copy(isDeleting = true, deleteConfirmBackup = null) }

            when (val result = backupRepository.deleteBackup(BackupId(backup.id))) {
                is AppResult.Success -> {
                    updateReady { ready ->
                        ready.copy(
                            backups = ready.backups.filter { b -> b.id != backup.id },
                            isDeleting = false,
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to delete backup: ${result.error.message}" }
                    updateReady {
                        it.copy(
                            isDeleting = false,
                            error = result.error,
                        )
                    }
                }
            }
        }
    }

    fun clearError() {
        updateReady { it.copy(error = null) }
    }

    /**
     * Apply [transform] to state only if it is currently [AdminBackupUiState.Ready].
     * No-ops when state is [AdminBackupUiState.Loading] or [AdminBackupUiState.Error].
     */
    private fun updateReady(transform: (AdminBackupUiState.Ready) -> AdminBackupUiState.Ready) {
        state.update { current ->
            if (current is AdminBackupUiState.Ready) transform(current) else current
        }
    }
}
