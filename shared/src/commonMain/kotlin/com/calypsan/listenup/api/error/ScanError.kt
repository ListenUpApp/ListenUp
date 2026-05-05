package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Scanner-domain error variants. Modelled as values, not exceptions — same
 * pattern as [AuthError]. Clients exhaustively `when` over the sealed shape
 * and never string-match.
 */
@Serializable
sealed interface ScanError : AppError {
    /** Another scan (full or incremental) is currently running. */
    @Serializable
    @SerialName("ScanError.AlreadyRunning")
    data class AlreadyRunning(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ScanError {
        override val message: String = "A scan is already running."
        override val code: String = "SCAN_ALREADY_RUNNING"
        override val isRetryable: Boolean = false
    }

    /** No `scanner.libraryPath` is configured. */
    @Serializable
    @SerialName("ScanError.LibraryPathNotConfigured")
    data class LibraryPathNotConfigured(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ScanError {
        override val message: String = "Library path is not configured."
        override val code: String = "SCAN_LIBRARY_PATH_NOT_CONFIGURED"
        override val isRetryable: Boolean = false
    }

    /** `scanner.libraryPath` is set but the directory doesn't exist or isn't readable. */
    @Serializable
    @SerialName("ScanError.LibraryPathNotFound")
    data class LibraryPathNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val path: String,
    ) : ScanError {
        override val message: String = "Library path does not exist or is not readable."
        override val code: String = "SCAN_LIBRARY_PATH_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /** Walker / Analyzer hit an unreadable file. The scan continues; this lands in `ScanResult.errors`. */
    @Serializable
    @SerialName("ScanError.FileUnreadable")
    data class FileUnreadable(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val path: String,
        override val message: String = "A file in the library could not be read.",
    ) : ScanError {
        override val code: String = "SCAN_FILE_UNREADABLE"
        override val isRetryable: Boolean = false
    }

    /** A `metadata.json` file failed to parse. The scan continues without the overlay. */
    @Serializable
    @SerialName("ScanError.MetadataParseError")
    data class MetadataParseError(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val path: String,
        override val message: String = "Could not read metadata for this file.",
    ) : ScanError {
        override val code: String = "SCAN_METADATA_PARSE_ERROR"
        override val isRetryable: Boolean = false
    }

    /** The Analyzer couldn't infer a usable title from the path or metadata. */
    @Serializable
    @SerialName("ScanError.TitleInferenceError")
    data class TitleInferenceError(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val path: String,
    ) : ScanError {
        override val message: String = "Could not infer a title for this audiobook."
        override val code: String = "SCAN_TITLE_INFERENCE_ERROR"
        override val isRetryable: Boolean = false
    }
}
