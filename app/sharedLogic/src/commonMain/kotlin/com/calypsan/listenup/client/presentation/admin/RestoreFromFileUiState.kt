package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.error.AppError

/** Screen state for picking + uploading a ListenUp backup file prior to restore. */
sealed interface RestoreFromFileUiState {
    /** Awaiting a file pick. */
    data object Idle : RestoreFromFileUiState

    /** The picked file ([filename]) is streaming to the server. */
    data class Uploading(
        val filename: String,
    ) : RestoreFromFileUiState

    /** The upload failed; [error] is the typed reason, surfaced to the user. */
    data class Error(
        val error: AppError,
    ) : RestoreFromFileUiState
}
