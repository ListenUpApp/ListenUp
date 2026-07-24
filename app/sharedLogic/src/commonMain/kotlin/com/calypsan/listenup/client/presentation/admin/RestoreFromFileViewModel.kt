package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.BackupRepository
import com.calypsan.listenup.core.BackupId
import com.calypsan.listenup.core.FileSource
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Pick + upload a ListenUp backup file, then hand the staged [BackupId] to the existing restore flow.
 * Upload-only — the destructive restore itself lives in [RestoreBackupViewModel].
 */
class RestoreFromFileViewModel(
    private val backupRepository: BackupRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val _state = MutableStateFlow<RestoreFromFileUiState>(RestoreFromFileUiState.Idle)
    val state: StateFlow<RestoreFromFileUiState> =
        _state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RestoreFromFileUiState.Idle)

    private val _navigation = Channel<BackupId>(Channel.BUFFERED)

    /** One-shot: a staged [BackupId] to navigate into the restore-confirmation flow. */
    val navigation: Flow<BackupId> = _navigation.receiveAsFlow()

    fun onFilePicked(fileSource: FileSource) {
        _state.value = RestoreFromFileUiState.Uploading(fileSource.filename)
        viewModelScope.launch {
            when (val result = backupRepository.uploadBackup(fileSource)) {
                is AppResult.Success -> {
                    // Reset before navigating so the spinner never lingers if navigation is delayed.
                    _state.value = RestoreFromFileUiState.Idle
                    _navigation.trySend(result.data.id)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    _state.value = RestoreFromFileUiState.Error(result.error)
                }
            }
        }
    }

    /** Dismiss the error and allow another pick. */
    fun reset() {
        _state.value = RestoreFromFileUiState.Idle
    }
}
