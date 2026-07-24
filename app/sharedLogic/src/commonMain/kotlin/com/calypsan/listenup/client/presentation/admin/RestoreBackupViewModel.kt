package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.BackupRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * UI state for restoring a single, already-selected backup.
 *
 * The server's restore is id-only and destructive — there is no mode, merge
 * strategy, dry run, or validate step. The flow is therefore: [Idle] →
 * (user requests) [Confirming] → (user confirms) [Restoring] →
 * [Completed]. A confirm-time failure returns to [Idle] carrying a transient
 * `error` for the snackbar.
 */
sealed interface RestoreBackupUiState {
    /**
     * Awaiting the user's decision to start the restore. Carries a transient
     * `error` set when a prior restore attempt failed.
     */
    data class Idle(
        val error: AppError? = null,
    ) : RestoreBackupUiState

    /** The destructive-action confirmation is being shown. */
    data object Confirming : RestoreBackupUiState

    /** The restore is in flight; live progress is exposed via [RestoreBackupViewModel.progress]. */
    data object Restoring : RestoreBackupUiState

    /** The restore completed; carries the server's [RestoreResult]. */
    data class Completed(
        val result: RestoreResult,
    ) : RestoreBackupUiState
}

/**
 * ViewModel for restoring a single backup, backed by the
 * [BackupService][com.calypsan.listenup.api.BackupService] RPC via [BackupRepository].
 *
 * Restore is destructive and irreversible, so it is gated behind a client-side
 * confirmation ([requestRestore] → [confirmRestore]) rather than a server wire
 * flag. Live restore progress is surfaced through [progress].
 */
class RestoreBackupViewModel(
    private val backupId: String,
    private val backupRepository: BackupRepository,
    private val syncRepository: SyncRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<RestoreBackupUiState>
        field = MutableStateFlow<RestoreBackupUiState>(RestoreBackupUiState.Idle())

    /**
     * Live restore progress streamed from the server. `null` until the first
     * [BackupEvent] arrives. Hot only while observed.
     */
    val progress: StateFlow<BackupEvent?> =
        backupRepository
            .observeProgress()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Show the destructive-action confirmation. */
    fun requestRestore() {
        state.value = RestoreBackupUiState.Confirming
    }

    /** Dismiss the confirmation without restoring. */
    fun cancelRestore() {
        state.value = RestoreBackupUiState.Idle()
    }

    /** Proceed with the restore after the user has confirmed. No-op unless [Confirming]. */
    fun confirmRestore() {
        if (state.value !is RestoreBackupUiState.Confirming) return
        state.value = RestoreBackupUiState.Restoring

        viewModelScope.launch {
            when (val result = backupRepository.restoreBackup(BackupId(backupId))) {
                is AppResult.Success -> {
                    // The server swapped its entire DB in-process. It broadcasts
                    // SyncControl.LibraryDataChanged to all connected devices (digest reconcile),
                    // but this initiating device also resyncs explicitly so the success state is
                    // only shown once local data is fresh — and so the FTS index is rebuilt,
                    // which the broadcast-triggered reconcile does not do.
                    when (val resync = syncRepository.forceFullResync()) {
                        is AppResult.Success -> {
                            state.value = RestoreBackupUiState.Completed(result.data)
                        }

                        is AppResult.Failure -> {
                            errorBus.emit(resync.error)
                            logger.error { "Restore succeeded but resync failed: ${resync.error.message}" }
                            state.value = RestoreBackupUiState.Idle(error = resync.error)
                        }
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to restore backup: ${result.error.message}" }
                    state.value = RestoreBackupUiState.Idle(error = result.error)
                }
            }
        }
    }
}
