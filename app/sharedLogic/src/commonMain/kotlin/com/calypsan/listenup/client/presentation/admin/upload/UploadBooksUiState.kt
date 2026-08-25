package com.calypsan.listenup.client.presentation.admin.upload

import com.calypsan.listenup.api.dto.uploads.UploadedBook
import com.calypsan.listenup.api.error.AppError

/** Screen state for uploading books into the library. */
sealed interface UploadBooksUiState {
    /** Awaiting a selection. */
    data object Idle : UploadBooksUiState

    /**
     * Files are streaming to the server.
     *
     * [fraction] is null when the selection could not report its sizes — an indeterminate bar is
     * honest, a bar pinned at zero is not.
     */
    data class Uploading(
        val fileIndex: Int,
        val fileCount: Int,
        val filename: String,
        val fraction: Float?,
    ) : UploadBooksUiState

    /** Every file is staged; the server is moving them into the library. */
    data object Finalizing : UploadBooksUiState

    /**
     * The session finished. Split three ways because the three outcomes want different words and
     * different follow-up actions — a duplicate is not a failure, and saying so matters.
     */
    data class Finished(
        val imported: List<UploadedBook>,
        val duplicates: List<UploadedBook>,
        val failed: List<UploadedBook>,
    ) : UploadBooksUiState

    /** The session failed as a whole; [error] is the typed reason. */
    data class Error(
        val error: AppError,
    ) : UploadBooksUiState
}
