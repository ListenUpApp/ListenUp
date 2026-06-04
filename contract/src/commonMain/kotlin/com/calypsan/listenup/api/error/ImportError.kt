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

    /** The requested import job does not exist or has already been deleted. */
    @Serializable
    @SerialName("ImportError.ImportNotFound")
    data class ImportNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ImportError {
        override val message: String = "That import no longer exists."
        override val code: String = "IMPORT_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * The confirmed mapping references a user or book that no longer exists, or
     * maps two ABS users to the same ListenUp user.
     */
    @Serializable
    @SerialName("ImportError.MappingInvalid")
    data class MappingInvalid(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ImportError {
        override val message: String = "The import mapping references something that no longer exists."
        override val code: String = "IMPORT_MAPPING_INVALID"
        override val isRetryable: Boolean = false
    }
}
