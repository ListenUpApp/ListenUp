package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from collection operations exposed through `CollectionService`.
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [com.calypsan.listenup.api.result.AppResult.Failure]. [correlationId] links
 * the server log line to the client's error display. [debugInfo] carries
 * per-instance technical detail for debug builds; [message] is the constant
 * user-facing string.
 *
 * [isRetryable] is `false` for all subtypes — collection failures require user
 * action (correct input, choose a different target user, etc.).
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`):
 * - [NotFound] / [BookNotFound] / [UserNotFound] → 404
 * - [Forbidden] → 403
 * - [InvalidInput] / [SystemCollectionReadOnly] / [SelfShare] / [AlreadyShared] → 400
 */
@Serializable
sealed interface CollectionError : AppError {
    /**
     * No collection with the given id exists, or the collection has been soft-deleted.
     * Raised when an operation targets a collection that cannot be found.
     */
    @Serializable
    @SerialName("CollectionError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CollectionError {
        override val message: String = "Collection not found."
        override val code: String = "COLLECTION_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * The caller does not have sufficient permission to perform the requested operation
     * on this collection. Raised when a non-owner attempts an owner-only action, or a
     * read-only share recipient attempts a write action.
     */
    @Serializable
    @SerialName("CollectionError.Forbidden")
    data class Forbidden(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CollectionError {
        override val message: String = "You don't have permission to do that."
        override val code: String = "COLLECTION_FORBIDDEN"
        override val isRetryable: Boolean = false
    }

    /**
     * The caller attempted to rename, delete, or change the sharing of a system collection.
     *
     * `ALL_BOOKS` and `INBOX` are server-managed system collections (created automatically
     * per library, owned by the `"system"` sentinel) and may not be renamed, deleted, or
     * re-shared — their grants are managed exclusively by the server.
     */
    @Serializable
    @SerialName("CollectionError.SystemCollectionReadOnly")
    data class SystemCollectionReadOnly(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CollectionError {
        override val message: String = "System collections can't be modified."
        override val code: String = "COLLECTION_SYSTEM_READ_ONLY"
        override val isRetryable: Boolean = false
    }

    /**
     * The request body failed validation (e.g. blank name, name too long).
     * Raised by create and rename operations when input constraints are violated.
     */
    @Serializable
    @SerialName("CollectionError.InvalidInput")
    data class InvalidInput(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CollectionError {
        override val message: String = "Invalid collection input."
        override val code: String = "COLLECTION_INVALID_INPUT"
        override val isRetryable: Boolean = false
    }

    /**
     * No book with the given id exists, or the book has been soft-deleted.
     * Raised when adding a book to a collection if the target book cannot be found.
     */
    @Serializable
    @SerialName("CollectionError.BookNotFound")
    data class BookNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CollectionError {
        override val message: String = "Book not found."
        override val code: String = "COLLECTION_BOOK_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * No user with the given id exists.
     * Raised when sharing a collection if the target user cannot be found.
     */
    @Serializable
    @SerialName("CollectionError.UserNotFound")
    data class UserNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CollectionError {
        override val message: String = "User not found."
        override val code: String = "COLLECTION_USER_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * The caller attempted to share a collection with themselves.
     * Sharing with the same user who owns the collection is not allowed.
     */
    @Serializable
    @SerialName("CollectionError.SelfShare")
    data class SelfShare(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CollectionError {
        override val message: String = "You can't share a collection with yourself."
        override val code: String = "COLLECTION_SELF_SHARE"
        override val isRetryable: Boolean = false
    }

    /**
     * The collection is already shared with the specified user at the same or a higher
     * permission level. Raised when attempting to create a duplicate share grant.
     */
    @Serializable
    @SerialName("CollectionError.AlreadyShared")
    data class AlreadyShared(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CollectionError {
        override val message: String = "This collection is already shared with that user."
        override val code: String = "COLLECTION_ALREADY_SHARED"
        override val isRetryable: Boolean = false
    }
}
