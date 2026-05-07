package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.domain.model.BackupInfo
import com.calypsan.listenup.client.domain.model.BackupValidation
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * UI state for the backup list screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first `listBackups()` emission.
 * - [Ready] once the backup list has loaded; carries backups, action overlays
 *   (`isCreating`, `isDeleting`, `validatingBackupId`), dialog state
 *   (`deleteConfirmBackup`, `validationResult`), and a transient `error` for
 *   mutation failures surfaced as a snackbar.
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
        val error: String? = null,
        val deleteConfirmBackup: BackupInfo? = null,
        val validationResult: BackupValidation? = null,
        val validatingBackupId: String? = null,
    ) : AdminBackupUiState

    /** Terminal state when the initial backup-list load fails. */
    data class Error(
        val message: String,
    ) : AdminBackupUiState
}

/**
 * ViewModel for managing backups.
 */
class AdminBackupViewModel(
    private val backupApi: BackupApiContract,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<AdminBackupUiState>
        field = MutableStateFlow<AdminBackupUiState>(AdminBackupUiState.Loading)

    init {
        loadBackups()
    }

    fun loadBackups() {
        viewModelScope.launch {
            try {
                val backups = backupApi.listBackups()
                val sorted =
                    backups
                        .map { b -> b.toDomain() }
                        .sortedByDescending { b -> b.createdAt }
                state.update { current ->
                    if (current is AdminBackupUiState.Ready) {
                        current.copy(backups = sorted, error = null)
                    } else {
                        // First emission (from Loading) or recovering from Error:
                        // transition to Ready with fresh data and default UI fields.
                        AdminBackupUiState.Ready(backups = sorted)
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to load backups" }
                state.update { current ->
                    if (current is AdminBackupUiState.Ready) {
                        // Transient refresh failure once already loaded: keep
                        // backups and surface error to the snackbar.
                        current.copy(error = "Failed to load backups: ${e.message}")
                    } else {
                        // Initial load (or post-Error retry) failed: terminal Error state.
                        AdminBackupUiState.Error("Failed to load backups: ${e.message}")
                    }
                }
            }
        }
    }

    fun createBackup(
        includeImages: Boolean,
        includeEvents: Boolean,
    ) {
        viewModelScope.launch {
            updateReady { it.copy(isCreating = true, error = null) }

            try {
                backupApi.createBackup(
                    includeImages = includeImages,
                    includeEvents = includeEvents,
                )
                // Reload list to show new backup
                loadBackups()
                updateReady { it.copy(isCreating = false) }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to create backup" }
                updateReady {
                    it.copy(
                        isCreating = false,
                        error = "Failed to create backup: ${e.message}",
                    )
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

            try {
                backupApi.deleteBackup(backup.id)
                updateReady { ready ->
                    ready.copy(
                        backups = ready.backups.filter { b -> b.id != backup.id },
                        isDeleting = false,
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to delete backup" }
                updateReady {
                    it.copy(
                        isDeleting = false,
                        error = "Failed to delete backup: ${e.message}",
                    )
                }
            }
        }
    }

    fun validateBackup(backup: BackupInfo) {
        viewModelScope.launch {
            updateReady { it.copy(validatingBackupId = backup.id) }

            try {
                val result = backupApi.validateBackup(backup.id)
                updateReady {
                    it.copy(
                        validatingBackupId = null,
                        validationResult =
                            BackupValidation(
                                valid = result.valid,
                                version = result.version,
                                serverName = result.serverName,
                                entityCounts = result.expectedCounts ?: emptyMap(),
                                errors = result.errors,
                                warnings = result.warnings,
                            ),
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Failed to validate backup" }
                updateReady {
                    it.copy(
                        validatingBackupId = null,
                        error = "Failed to validate backup: ${e.message}",
                    )
                }
            }
        }
    }

    fun dismissValidation() {
        updateReady { it.copy(validationResult = null) }
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

    private fun com.calypsan.listenup.client.data.remote.model.BackupResponse.toDomain() =
        BackupInfo(
            id = id,
            path = path,
            size = size,
            createdAt =
                try {
                    Instant.parse(createdAt)
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorBus.emit(ErrorMapper.map(e))
                    Instant.DISTANT_PAST
                },
            checksum = checksum,
        )
}
