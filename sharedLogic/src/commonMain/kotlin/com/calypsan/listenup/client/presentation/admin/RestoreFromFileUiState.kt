package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.AppError

/** Screen state for picking + uploading a ListenUp backup file prior to restore. */
sealed interface RestoreFromFileUiState {
    data object Idle : RestoreFromFileUiState
    data class Uploading(val filename: String) : RestoreFromFileUiState
    data class Error(val error: AppError) : RestoreFromFileUiState
}
