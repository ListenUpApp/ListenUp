package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Domain errors for the admin book-upload surface.
 *
 * An upload is a session: create → stream N files → finalize. Each step fails for its own reasons
 * and the admin needs to know which one, because the remedies differ — retry the one file, drop
 * something from the selection, or fix the library folder.
 *
 * Duplicates are deliberately **not** here: a session can hold several books, and one being
 * already in the library says nothing about the others. That outcome rides per-book on
 * [com.calypsan.listenup.api.dto.uploads.UploadedBook] instead of failing the whole request.
 */
@Serializable
sealed interface UploadError : AppError {
    /**
     * The upload session does not exist — it was already finalized, abandoned, or its id is not
     * one this server minted. Not retryable: the staged files are gone, so the client must start
     * a fresh session.
     */
    @Serializable
    @SerialName("UploadError.SessionNotFound")
    data class SessionNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : UploadError {
        override val message: String = "That upload is no longer in progress."
        override val code: String = "UPLOAD_SESSION_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * The file's path within the upload was refused before a single byte was read.
     *
     * A client-supplied relative path is untrusted input that decides where bytes land, so it is
     * validated against absolute paths, `..` traversal, empty and reserved segments, and anything
     * that does not resolve strictly inside the session's own staging directory. Not retryable —
     * re-sending the same path changes nothing.
     */
    @Serializable
    @SerialName("UploadError.InvalidFilePath")
    data class InvalidFilePath(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : UploadError {
        override val message: String = "That file path isn't allowed."
        override val code: String = "UPLOAD_INVALID_FILE_PATH"
        override val isRetryable: Boolean = false
    }

    /**
     * The upload exceeds what one session may hold — too many files, or too many bytes in total.
     * Not retryable: the same selection will exceed the same limit. Split it into smaller uploads.
     */
    @Serializable
    @SerialName("UploadError.SessionTooLarge")
    data class SessionTooLarge(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : UploadError {
        override val message: String = "That upload is too large. Try uploading fewer books at once."
        override val code: String = "UPLOAD_SESSION_TOO_LARGE"
        override val isRetryable: Boolean = false
    }

    /**
     * A single file did not arrive intact — the request carried no file part, or the transfer was
     * cut short. Retryable: re-sending that one file is exactly the right response, and the
     * session's other files are untouched.
     */
    @Serializable
    @SerialName("UploadError.FileTransferFailed")
    data class FileTransferFailed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : UploadError {
        override val message: String = "That file didn't upload completely. Try it again."
        override val code: String = "UPLOAD_FILE_TRANSFER_FAILED"
        override val isRetryable: Boolean = true
    }

    /**
     * Finalize found no audiobook in the staged tree — the selection held documents or images but
     * no recognised audio, so there is no book to create. Not retryable.
     */
    @Serializable
    @SerialName("UploadError.NoBooksFound")
    data class NoBooksFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : UploadError {
        override val message: String = "No audiobooks were found in that upload."
        override val code: String = "UPLOAD_NO_BOOKS_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * There is nowhere to put the book: no library folder is configured, so the server has no
     * root it is permitted to write inside. Not retryable until an admin adds a library folder.
     */
    @Serializable
    @SerialName("UploadError.NoLibraryFolder")
    data class NoLibraryFolder(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : UploadError {
        override val message: String = "Add a library folder before uploading books."
        override val code: String = "UPLOAD_NO_LIBRARY_FOLDER"
        override val isRetryable: Boolean = false
    }
}
