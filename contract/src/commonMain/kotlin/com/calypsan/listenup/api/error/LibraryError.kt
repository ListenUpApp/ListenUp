package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from library and library-folder lifecycle operations.
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [com.calypsan.listenup.api.result.AppResult.Failure]. [correlationId] links
 * the server log line to the client's error display. [debugInfo] carries
 * per-instance technical detail (e.g. the offending path) for debug builds;
 * [message] is the constant user-facing string.
 *
 * All subtypes have [isRetryable] = `false`: every library failure requires
 * explicit user action (correct the path, choose a different name, etc.).
 * Retry middleware must not blindly re-fire these requests.
 *
 * HTTP status mapping (applied in `AppErrorStatusPages.kt`):
 * - [NotFound] → 404
 * - [InvalidPath] → 400
 * - [DuplicateFolder] → 409
 * - [FolderNotFound] → 404
 */
@Serializable
sealed interface LibraryError : AppError {

    /**
     * No library exists with the given id.
     *
     * Returned by operations that address a specific library (rename, delete,
     * scan, add folder) when the id is not found in the database.
     */
    @Serializable
    @SerialName("LibraryError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : LibraryError {
        override val message: String = "Library not found."
        override val code: String = "LIBRARY_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * A supplied folder path does not exist or is not readable by the server process.
     *
     * Returned by [com.calypsan.listenup.api.LibraryAdminService.createLibrary]
     * and [com.calypsan.listenup.api.LibraryAdminService.addFolder] when the
     * server cannot verify the path is accessible.
     */
    @Serializable
    @SerialName("LibraryError.InvalidPath")
    data class InvalidPath(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : LibraryError {
        override val message: String = "The path does not exist or is not readable."
        override val code: String = "LIBRARY_INVALID_PATH"
        override val isRetryable: Boolean = false
    }

    /**
     * The folder path is already registered under another library.
     *
     * Each folder path may be registered under at most one library at a time.
     * A unique index on `library_folders.root_path` (where `deleted_at IS NULL`)
     * enforces this constraint at the database level.
     */
    @Serializable
    @SerialName("LibraryError.DuplicateFolder")
    data class DuplicateFolder(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : LibraryError {
        override val message: String = "That folder is already registered under another library."
        override val code: String = "LIBRARY_DUPLICATE_FOLDER"
        override val isRetryable: Boolean = false
    }

    /**
     * No folder exists with the given id.
     *
     * Returned by operations that address a specific folder (remove, scan) when
     * the id is not found in the database.
     */
    @Serializable
    @SerialName("LibraryError.FolderNotFound")
    data class FolderNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : LibraryError {
        override val message: String = "Folder not found."
        override val code: String = "LIBRARY_FOLDER_NOT_FOUND"
        override val isRetryable: Boolean = false
    }
}
