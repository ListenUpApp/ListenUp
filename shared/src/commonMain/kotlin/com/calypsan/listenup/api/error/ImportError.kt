package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain errors for ABS (Audiobookshelf) import operations.
 *
 * Import is a multi-step process: upload -> analyze -> preview -> apply.
 * Each step can fail independently and the user needs to know which step
 * failed so they can take appropriate action.
 */
@Serializable
sealed interface ImportError : AppError {

    /**
     * Backup file upload failed.
     *
     * Most common cause: network timeout on large files (>50MB).
     * Suggestion: use LAN connection instead of remote/VPN.
     */
    @Serializable
    @SerialName("ImportError.UploadFailed")
    data class UploadFailed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ImportError {
        override val message: String = "Failed to upload backup file. Check your connection."
        override val code: String = "IMPORT_UPLOAD_FAILED"
        override val isRetryable: Boolean = true
    }

    /**
     * Server-side analysis of the backup failed.
     *
     * The file uploaded successfully but the server couldn't process it.
     * Could be a corrupt backup or transient server resource constraints.
     */
    @Serializable
    @SerialName("ImportError.AnalysisFailed")
    data class AnalysisFailed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ImportError {
        override val message: String = "Server failed to analyze the backup."
        override val code: String = "IMPORT_ANALYSIS_FAILED"
        override val isRetryable: Boolean = true
    }

    /** Applying import results (matching/creating books) failed. */
    @Serializable
    @SerialName("ImportError.ApplyFailed")
    data class ApplyFailed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ImportError {
        override val message: String = "Failed to apply import changes."
        override val code: String = "IMPORT_APPLY_FAILED"
        override val isRetryable: Boolean = true
    }
}
