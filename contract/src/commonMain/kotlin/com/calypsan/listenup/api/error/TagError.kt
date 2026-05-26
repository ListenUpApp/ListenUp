package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from tag operations exposed through
 * [com.calypsan.listenup.api.TagService].
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [com.calypsan.listenup.api.result.AppResult.Failure]. [correlationId] links
 * the server log line to the client's error display. [debugInfo] carries
 * per-instance technical detail for debug builds; [message] is the constant
 * user-facing string.
 *
 * [isRetryable] is a strict middleware contract — `false` for all subtypes because
 * tag failures require user action (correct input, retry with a valid name, etc.).
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`):
 * - [NotFound] / [BookNotFound] → 404
 * - [InvalidName] / [NameTooLong] → 400
 */
@Serializable
sealed interface TagError : AppError {
    /**
     * No tag with the given id exists, or the tag has been soft-deleted.
     * Raised by [com.calypsan.listenup.api.TagService.renameTag] and
     * [com.calypsan.listenup.api.TagService.deleteTag].
     */
    @Serializable
    @SerialName("TagError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : TagError {
        override val message: String = "Tag not found."
        override val code: String = "TAG_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * The tag name is empty, blank, or resolves to an empty slug after normalization
     * (i.e. contains only special characters). Raised by
     * [com.calypsan.listenup.api.TagService.addTagToBook] and
     * [com.calypsan.listenup.api.TagService.renameTag].
     */
    @Serializable
    @SerialName("TagError.InvalidName")
    data class InvalidName(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : TagError {
        override val message: String = "Tag name is empty or contains only special characters."
        override val code: String = "TAG_INVALID_NAME"
        override val isRetryable: Boolean = false
    }

    /**
     * The tag name exceeds the 64-character limit. Raised by
     * [com.calypsan.listenup.api.TagService.addTagToBook] and
     * [com.calypsan.listenup.api.TagService.renameTag].
     */
    @Serializable
    @SerialName("TagError.NameTooLong")
    data class NameTooLong(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : TagError {
        override val message: String = "Tag name exceeds the 64-character limit."
        override val code: String = "TAG_NAME_TOO_LONG"
        override val isRetryable: Boolean = false
    }

    /**
     * No book with the given id exists, or the book has been soft-deleted.
     * Raised by [com.calypsan.listenup.api.TagService.addTagToBook] when
     * the target book cannot be found.
     */
    @Serializable
    @SerialName("TagError.BookNotFound")
    data class BookNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : TagError {
        override val message: String = "Book not found."
        override val code: String = "TAG_BOOK_NOT_FOUND"
        override val isRetryable: Boolean = false
    }
}
