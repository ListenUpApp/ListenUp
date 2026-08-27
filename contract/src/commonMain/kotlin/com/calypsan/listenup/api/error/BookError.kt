package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from book operations exposed through
 * [com.calypsan.listenup.api.BookService].
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [com.calypsan.listenup.api.result.AppResult.Failure]. [correlationId] links
 * the server log line to the client's error display. [debugInfo] carries
 * per-instance technical detail for debug builds; [message] is the constant
 * user-facing string.
 *
 * [isRetryable] is `false` for all subtypes — book failures require user action
 * (correct the input, verify the book exists, etc.).
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`):
 * - [NotFound] → 404
 * - [InvalidInput] → 400
 * - [FolderNotExclusive] → 409
 */
@Serializable
sealed interface BookError : AppError {
    /**
     * No book with the given id exists, or the book has been soft-deleted.
     * Raised by mutations that address a specific book when it cannot be found.
     */
    @Serializable
    @SerialName("BookError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : BookError {
        override val message: String = "This book no longer exists."
        override val code: String = "BOOK_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * A supplied field value failed validation — empty title, out-of-range year,
     * or any other constraint enforced at the API boundary.
     * Raised by mutations that accept user-supplied book metadata.
     */
    @Serializable
    @SerialName("BookError.InvalidInput")
    data class InvalidInput(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : BookError {
        override val message: String = "Some of the changes couldn't be saved."
        override val code: String = "BOOK_INVALID_INPUT"
        override val isRetryable: Boolean = false
    }

    /**
     * Deleting this book would take another live book's files with it: [otherBookTitle] lives in
     * the same directory, or in one nested beneath it.
     *
     * Raised by [com.calypsan.listenup.api.BookService.deleteBook], which removes the book's whole
     * directory. Two books at the same stored path are impossible (a UNIQUE index on the natural
     * key), but that index does not reach the shapes that matter here: a book **nested** under
     * another's directory, and two library folders that overlap on disk, where distinct stored
     * paths resolve to one place. So the check runs at delete time, every time, and refusing is the
     * only safe answer — a silent skip would delete the requested book's files and quietly orphan
     * the other book's row.
     *
     * [otherBookId] and [otherBookTitle] ride the error rather than the constant [message] because
     * a body-level message is a per-subtype constant; the UI names the blocking book from these
     * fields.
     */
    @Serializable
    @SerialName("BookError.FolderNotExclusive")
    data class FolderNotExclusive(
        /** Id of the other live book sharing (or nested inside) this book's directory. */
        @SerialName("otherBookId")
        val otherBookId: String,
        /** Title of that other book, for the refusal the user reads. */
        @SerialName("otherBookTitle")
        val otherBookTitle: String,
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : BookError {
        override val message: String = "Another book shares this book's folder, so it can't be deleted."
        override val code: String = "BOOK_FOLDER_NOT_EXCLUSIVE"
        override val isRetryable: Boolean = false
    }
}
