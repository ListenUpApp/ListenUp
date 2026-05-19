package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain errors for audiobook download operations.
 *
 * Downloads are user-initiated, so failures need to surface — the user is
 * waiting for content to be available offline.
 */
@Serializable
sealed interface DownloadError : AppError {
    /** Download of an audiobook failed. */
    @Serializable
    @SerialName("DownloadError.DownloadFailed")
    data class DownloadFailed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val bookTitle: String? = null,
    ) : DownloadError {
        override val message: String =
            bookTitle?.let { "Failed to download \"$it\"." }
                ?: "Download failed."
        override val code: String = "DOWNLOAD_FAILED"
        override val isRetryable: Boolean = true
    }

    /** Not enough storage space to complete download. User must free space. */
    @Serializable
    @SerialName("DownloadError.InsufficientStorage")
    data class InsufficientStorage(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val bookTitle: String? = null,
    ) : DownloadError {
        override val message: String =
            bookTitle?.let { "Not enough space to download \"$it\"." }
                ?: "Not enough storage space."
        override val code: String = "DOWNLOAD_INSUFFICIENT_STORAGE"
        override val isRetryable: Boolean = false
    }

    /** Transcoding job timed out (24h backstop) or server lost the job. */
    @Serializable
    @SerialName("DownloadError.TranscodeTimeout")
    data class TranscodeTimeout(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val transcodeJobId: String,
        val bookTitle: String? = null,
    ) : DownloadError {
        override val message: String =
            bookTitle?.let { "Transcoding timed out for \"$it\". Please try again." }
                ?: "Transcoding timed out. Please try again."
        override val code: String = "DOWNLOAD_TRANSCODE_TIMEOUT"
        override val isRetryable: Boolean = true
    }
}
