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
    ) : ScanError

    /** No `scanner.libraryPath` is configured. */
    @Serializable
    @SerialName("ScanError.LibraryPathNotConfigured")
    data class LibraryPathNotConfigured(
        override val correlationId: String? = null,
    ) : ScanError

    /** `scanner.libraryPath` is set but the directory doesn't exist or isn't readable. */
    @Serializable
    @SerialName("ScanError.LibraryPathNotFound")
    data class LibraryPathNotFound(
        val path: String,
        override val correlationId: String? = null,
    ) : ScanError

    /** Walker / Analyzer hit an unreadable file. The scan continues; this lands in `ScanResult.errors`. */
    @Serializable
    @SerialName("ScanError.FileUnreadable")
    data class FileUnreadable(
        val path: String,
        val message: String,
        override val correlationId: String? = null,
    ) : ScanError

    /** A `metadata.json` file failed to parse. The scan continues without the overlay. */
    @Serializable
    @SerialName("ScanError.MetadataParseError")
    data class MetadataParseError(
        val path: String,
        val message: String,
        override val correlationId: String? = null,
    ) : ScanError

    /** The Analyzer couldn't infer a usable title from the path or metadata. */
    @Serializable
    @SerialName("ScanError.TitleInferenceError")
    data class TitleInferenceError(
        val path: String,
        override val correlationId: String? = null,
    ) : ScanError
}
