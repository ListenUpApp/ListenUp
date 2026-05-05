package com.calypsan.listenup.client.core.error

/**
 * Domain errors for ABS (Audiobookshelf) import operations.
 *
 * Import is a multi-step process: upload → analyze → preview → apply.
 * Each step can fail independently and the user needs to know which
 * step failed so they can take appropriate action.
 */
@Deprecated(
    message = "Migrate to `com.calypsan.listenup.api.error.ImportError`. Deleted in Task 16 of Phase 3.",
    level = DeprecationLevel.WARNING,
)
sealed interface ImportError : AppError {
    /**
     * Backup file upload failed.
     *
     * Most common cause: network timeout on large files (>50MB).
     * Suggestion: use LAN connection instead of remote/VPN.
     */
    data class UploadFailed(
        override val debugInfo: String? = null,
    ) : ImportError {
        override val message = "Failed to upload backup file. Check your connection."
        override val code = "IMPORT_UPLOAD_FAILED"
        override val isRetryable = true
    }

    /**
     * Server-side analysis of the backup failed.
     *
     * The file uploaded successfully but the server couldn't process it.
     * Could be a corrupt backup or server resource constraints.
     */
    data class AnalysisFailed(
        override val debugInfo: String? = null,
    ) : ImportError {
        override val message = "Server failed to analyze the backup."
        override val code = "IMPORT_ANALYSIS_FAILED"
        override val isRetryable = true
    }

    /**
     * Applying import results (matching/creating books) failed.
     */
    data class ApplyFailed(
        override val debugInfo: String? = null,
    ) : ImportError {
        override val message = "Failed to apply import changes."
        override val code = "IMPORT_APPLY_FAILED"
        override val isRetryable = true
    }
}
