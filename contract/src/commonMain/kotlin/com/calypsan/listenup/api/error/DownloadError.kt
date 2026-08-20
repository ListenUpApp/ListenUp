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

    /**
     * This platform's download backend cannot complete a download at all — e.g. a browser,
     * where offline audio is undesigned rather than temporarily unavailable. Distinct from
     * [DownloadFailed]: that one is a transient failure worth retrying, this one never will be,
     * no matter how many times it's asked.
     */
    @Serializable
    @SerialName("DownloadError.NotSupported")
    data class NotSupported(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val bookTitle: String? = null,
    ) : DownloadError {
        override val message: String = "Downloads aren't supported on this device."
        override val code: String = "DOWNLOAD_NOT_SUPPORTED"
        override val isRetryable: Boolean = false
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

    /**
     * Deleting a downloaded book's local files failed (partially or entirely). The download row
     * is left untouched — never marked deleted — so the listener can retry rather than the app
     * silently reporting reclaimed space that was never freed.
     */
    @Serializable
    @SerialName("DownloadError.DeleteFailed")
    data class DeleteFailed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val bookTitle: String? = null,
    ) : DownloadError {
        override val message: String =
            bookTitle?.let { "Failed to delete \"$it\"." }
                ?: "Failed to delete download."
        override val code: String = "DOWNLOAD_DELETE_FAILED"
        override val isRetryable: Boolean = true
    }
}
