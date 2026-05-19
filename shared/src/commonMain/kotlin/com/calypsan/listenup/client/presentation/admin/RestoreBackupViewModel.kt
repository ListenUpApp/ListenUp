package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.model.RestoreError
import com.calypsan.listenup.client.data.remote.model.RestoreRequest
import com.calypsan.listenup.client.domain.model.BackupValidation
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.presentation.error.userMessageFor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Restore mode - fresh wipe or merge with existing data.
 * API values must match server's backup.RestoreMode constants.
 */
enum class RestoreMode(
    val apiValue: String,
    val displayName: String,
    val description: String,
) {
    FRESH("full", "Fresh Restore", "Wipe all existing data and restore from backup"),
    MERGE("merge", "Merge", "Keep existing data and merge with backup"),
}

/**
 * Merge strategy when using merge mode.
 * API values must match server's backup.MergeStrategy constants.
 */
enum class MergeStrategy(
    val apiValue: String,
    val displayName: String,
    val description: String,
) {
    KEEP_LOCAL("keep_local", "Keep Local", "Keep existing local data on conflicts"),
    KEEP_BACKUP("keep_backup", "Keep Backup", "Replace with backup data on conflicts"),
    NEWEST("newest", "Newest Wins", "Keep the most recently modified version"),
}

/**
 * Step in the restore wizard.
 */
enum class RestoreStep {
    MODE_SELECTION,
    MERGE_STRATEGY,
    VALIDATION,
    CONFIRMATION,
    RESTORING,
    RESULTS,
}

/**
 * UI state for the restore backup wizard.
 *
 * Sealed hierarchy:
 * - [Loading] before the initial `validateBackup()` call completes.
 * - [Ready] once initial validation resolves (successfully or with a
 *   recoverable error surfaced via `error`); carries wizard step, mode,
 *   merge strategy, overlays (`isValidating`, `isRestoring`), dry-run and
 *   restore results, and a transient `error` for mutation failures.
 * - [Error] terminal state when the initial `validateBackup()` throws.
 */
sealed interface RestoreBackupUiState {
    data object Loading : RestoreBackupUiState

    /**
     * Wizard is interactive; carries the current [step], chosen mode/strategy,
     * action overlays, dry-run/restore results, and a transient `error`.
     */
    data class Ready(
        val backupId: String,
        val step: RestoreStep = RestoreStep.MODE_SELECTION,
        val mode: RestoreMode? = null,
        val mergeStrategy: MergeStrategy? = null,
        val isValidating: Boolean = false,
        val validation: BackupValidation? = null,
        val dryRunResults: DryRunResults? = null,
        val isRestoring: Boolean = false,
        val restoreResults: RestoreResults? = null,
        val error: String? = null,
    ) : RestoreBackupUiState

    /** Terminal state when the initial `validateBackup()` call throws. */
    data class Error(
        val message: String,
    ) : RestoreBackupUiState
}

/**
 * Results from a dry run.
 */
data class DryRunResults(
    val willImport: Map<String, Int>,
    val willSkip: Map<String, Int>,
    val errors: List<RestoreError>,
    val duration: String,
)

/**
 * Results from the actual restore.
 */
data class RestoreResults(
    val imported: Map<String, Int>,
    val skipped: Map<String, Int>,
    val errors: List<RestoreError>,
    val duration: String,
)

/**
 * ViewModel for restore backup flow.
 */
class RestoreBackupViewModel(
    private val backupId: String,
    private val backupApi: BackupApiContract,
    private val syncRepository: SyncRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<RestoreBackupUiState>
        field = MutableStateFlow<RestoreBackupUiState>(RestoreBackupUiState.Loading)

    init {
        validateBackup()
    }

    private fun validateBackup() {
        viewModelScope.launch {
            when (val result = backupApi.validateBackup(backupId)) {
                is AppResult.Success -> {
                    val response = result.data
                    val validation =
                        BackupValidation(
                            valid = response.valid,
                            version = response.version,
                            serverName = response.serverName,
                            entityCounts = response.expectedCounts ?: emptyMap(),
                            errors = response.errors,
                            warnings = response.warnings,
                        )
                    state.update { current ->
                        if (current is RestoreBackupUiState.Ready) {
                            current.copy(isValidating = false, validation = validation, error = null)
                        } else {
                            // First emission (from Loading) or recovering from Error:
                            // transition to Ready with freshly validated backup metadata.
                            RestoreBackupUiState.Ready(
                                backupId = backupId,
                                validation = validation,
                            )
                        }
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to validate backup: ${result.error.message}" }
                    state.update { current ->
                        if (current is RestoreBackupUiState.Ready) {
                            // Re-validation after reaching Ready failed: keep wizard state,
                            // surface error transiently.
                            current.copy(
                                isValidating = false,
                                error = userMessageFor(result.error),
                            )
                        } else {
                            // Initial validation (from Loading or Error retry) failed: terminal.
                            RestoreBackupUiState.Error(userMessageFor(result.error))
                        }
                    }
                }
            }
        }
    }

    fun selectMode(mode: RestoreMode) {
        updateReady { it.copy(mode = mode, error = null) }
    }

    fun selectMergeStrategy(strategy: MergeStrategy) {
        updateReady { it.copy(mergeStrategy = strategy, error = null) }
    }

    fun nextStep() {
        val current = state.value as? RestoreBackupUiState.Ready ?: return
        val nextStep =
            when (current.step) {
                RestoreStep.MODE_SELECTION -> {
                    if (current.mode == RestoreMode.MERGE) {
                        RestoreStep.MERGE_STRATEGY
                    } else {
                        RestoreStep.VALIDATION
                    }
                }

                RestoreStep.MERGE_STRATEGY -> {
                    RestoreStep.VALIDATION
                }

                RestoreStep.VALIDATION -> {
                    RestoreStep.CONFIRMATION
                }

                RestoreStep.CONFIRMATION -> {
                    performRestore()
                    RestoreStep.RESTORING
                }

                RestoreStep.RESTORING -> {
                    RestoreStep.RESULTS
                }

                RestoreStep.RESULTS -> {
                    RestoreStep.RESULTS
                }
            }
        updateReady { it.copy(step = nextStep) }
    }

    fun previousStep() {
        val current = state.value as? RestoreBackupUiState.Ready ?: return
        val prevStep =
            when (current.step) {
                RestoreStep.MODE_SELECTION -> {
                    RestoreStep.MODE_SELECTION
                }

                RestoreStep.MERGE_STRATEGY -> {
                    RestoreStep.MODE_SELECTION
                }

                RestoreStep.VALIDATION -> {
                    if (current.mode == RestoreMode.MERGE) {
                        RestoreStep.MERGE_STRATEGY
                    } else {
                        RestoreStep.MODE_SELECTION
                    }
                }

                RestoreStep.CONFIRMATION -> {
                    RestoreStep.VALIDATION
                }

                RestoreStep.RESTORING -> {
                    RestoreStep.RESTORING
                }

                // Can't go back during restore
                RestoreStep.RESULTS -> {
                    RestoreStep.RESULTS
                } // Can't go back after complete
            }
        updateReady { it.copy(step = prevStep) }
    }

    fun performDryRun() {
        viewModelScope.launch {
            updateReady { it.copy(isValidating = true, error = null) }

            val current = state.value as? RestoreBackupUiState.Ready
            when (
                val result =
                    backupApi.restore(
                        RestoreRequest(
                            backupId = backupId,
                            mode = current?.mode?.apiValue ?: RestoreMode.MERGE.apiValue,
                            mergeStrategy = current?.mergeStrategy?.apiValue,
                            dryRun = true,
                            confirmFullWipe = false,
                        ),
                    )
            ) {
                is AppResult.Success -> {
                    val response = result.data
                    updateReady {
                        it.copy(
                            isValidating = false,
                            dryRunResults =
                                DryRunResults(
                                    willImport = response.imported,
                                    willSkip = response.skipped,
                                    errors = response.errors,
                                    duration = response.duration,
                                ),
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to perform dry run: ${result.error.message}" }
                    updateReady {
                        it.copy(
                            isValidating = false,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }

    private fun performRestore() {
        viewModelScope.launch {
            updateReady { it.copy(isRestoring = true, error = null) }

            val current = state.value as? RestoreBackupUiState.Ready
            when (
                val result =
                    backupApi.restore(
                        RestoreRequest(
                            backupId = backupId,
                            mode = current?.mode?.apiValue ?: RestoreMode.MERGE.apiValue,
                            mergeStrategy = current?.mergeStrategy?.apiValue,
                            dryRun = false,
                            confirmFullWipe = current?.mode == RestoreMode.FRESH,
                        ),
                    )
            ) {
                is AppResult.Success -> {
                    val response = result.data

                    // After server restore completes, sync client state
                    // FRESH: Server data was completely replaced, need to clear and resync
                    // MERGE: Server data was merged, need to refresh listening history
                    logger.info { "Restore complete, syncing client state for mode ${current?.mode}" }
                    if (current?.mode == RestoreMode.FRESH) {
                        // Clear local database and do full resync
                        syncRepository.forceFullResync()
                    } else {
                        // Merge mode - just refresh listening history like ABS import
                        syncRepository.refreshListeningHistory()
                    }

                    updateReady {
                        it.copy(
                            isRestoring = false,
                            step = RestoreStep.RESULTS,
                            restoreResults =
                                RestoreResults(
                                    imported = response.imported,
                                    skipped = response.skipped,
                                    errors = response.errors,
                                    duration = response.duration,
                                ),
                        )
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to restore backup: ${result.error.message}" }
                    updateReady {
                        it.copy(
                            isRestoring = false,
                            error = userMessageFor(result.error),
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
     * Apply [transform] to state only if it is currently [RestoreBackupUiState.Ready].
     * No-ops when state is [RestoreBackupUiState.Loading] or [RestoreBackupUiState.Error].
     */
    private fun updateReady(transform: (RestoreBackupUiState.Ready) -> RestoreBackupUiState.Ready) {
        state.update { current ->
            if (current is RestoreBackupUiState.Ready) transform(current) else current
        }
    }
}
