package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.domain.model.BackupInfo
import com.calypsan.listenup.client.domain.model.BackupValidation
import com.calypsan.listenup.client.presentation.error.userMessageFor
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
            when (val result = backupApi.listBackups()) {
                is AppResult.Success -> {
                    val sorted =
                        result.data
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
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to load backups: ${result.error.message}" }
                    state.update { current ->
                        if (current is AdminBackupUiState.Ready) {
                            // Transient refresh failure once already loaded: keep
                            // backups and surface error to the snackbar.
                            current.copy(error = userMessageFor(result.error))
                        } else {
                            // Initial load (or post-Error retry) failed: terminal Error state.
                            AdminBackupUiState.Error(userMessageFor(result.error))
                        }
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

            when (
                val result =
                    backupApi.createBackup(
                        includeImages = includeImages,
                        includeEvents = includeEvents,
                    )
            ) {
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
                            error = userMessageFor(result.error),
                        )
                    }
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

            when (val result = backupApi.deleteBackup(backup.id)) {
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
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    fun validateBackup(backup: BackupInfo) {
        viewModelScope.launch {
            updateReady { it.copy(validatingBackupId = backup.id) }

            when (val result = backupApi.validateBackup(backup.id)) {
                is AppResult.Success -> {
                    val response = result.data
                    updateReady {
                        it.copy(
                            validatingBackupId = null,
                            validationResult =
                                BackupValidation(
                                    valid = response.valid,
                                    version = response.version,
                                    serverName = response.serverName,
                                    entityCounts = response.expectedCounts ?: emptyMap(),
                                    errors = response.errors,
                                    warnings = response.warnings,
                                ),
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to validate backup: ${result.error.message}" }
                    updateReady {
                        it.copy(
                            validatingBackupId = null,
                            error = userMessageFor(result.error),
                        )
                    }
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
                    // Date parsing is not an API call — keep try/catch for this non-API failure.
                    // Use DISTANT_PAST as graceful fallback so the backup still appears in list.
                    logger.warn(e) { "Failed to parse backup date '$createdAt', using DISTANT_PAST" }
                    Instant.DISTANT_PAST
                },
            checksum = checksum,
        )
}
