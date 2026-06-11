package com.calypsan.listenup.client.presentation.admin.absimport

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.BackupApiContract
import com.calypsan.listenup.client.data.remote.model.ImportABSRequest
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.presentation.admin.ABSImportResults
import com.calypsan.listenup.client.presentation.admin.ABSImportStep
import com.calypsan.listenup.client.presentation.admin.ABSImportUiState
import com.calypsan.listenup.client.presentation.error.userMessageFor
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

internal class ImportDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ABSImportUiState>,
    private val errorBus: ErrorBus,
    private val backupApi: BackupApiContract,
    private val syncRepository: SyncRepository,
) {
    fun setImportSessions(value: Boolean) {
        state.updateReady { it.copy(importSessions = value) }
    }

    fun setImportProgress(value: Boolean) {
        state.updateReady { it.copy(importProgress = value) }
    }

    fun setRebuildProgress(value: Boolean) {
        state.updateReady { it.copy(rebuildProgress = value) }
    }

    fun performImport() {
        scope.launch {
            state.updateReady { it.copy(step = ABSImportStep.IMPORTING, isImporting = true, error = null) }

            val current = state.value as? ABSImportUiState.Ready ?: return@launch
            when (
                val result =
                    backupApi.importABSBackup(
                        ImportABSRequest(
                            backupPath = current.backupPath,
                            userMappings = current.userMappings,
                            bookMappings = current.bookMappings,
                            importSessions = current.importSessions,
                            importProgress = current.importProgress,
                            rebuildProgress = current.rebuildProgress,
                        ),
                    )
            ) {
                is AppResult.Success -> {
                    val importResult = result.data
                    state.updateReady {
                        it.copy(
                            isImporting = false,
                            step = ABSImportStep.RESULTS,
                            importResults =
                                ABSImportResults(
                                    sessionsImported = importResult.sessionsImported,
                                    sessionsSkipped = importResult.sessionsSkipped,
                                    progressImported = importResult.progressImported,
                                    progressSkipped = importResult.progressSkipped,
                                    eventsCreated = importResult.eventsCreated,
                                    affectedUsers = importResult.affectedUsers,
                                    duration = importResult.duration,
                                    warnings = importResult.warnings,
                                    errors = importResult.errors,
                                ),
                        )
                    }

                    // Refresh listening history to pull all imported events and rebuild positions
                    // This uses a full refresh (ignoring delta sync cursor) because imported
                    // events have historical timestamps that wouldn't be included in normal sync
                    logger.info { "Import complete, refreshing listening history" }
                    syncRepository.refreshListeningHistory()
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to import ABS backup: ${result.error.message}" }
                    state.updateReady {
                        it.copy(
                            isImporting = false,
                            step = ABSImportStep.IMPORT_OPTIONS,
                            error = userMessageFor(result.error),
                        )
                    }
                }
            }
        }
    }
}
