package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from mood operations exposed through
 * [com.calypsan.listenup.api.MoodService].
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [com.calypsan.listenup.api.result.AppResult.Failure]. [correlationId] links
 * the server log line to the client's error display. [debugInfo] carries
 * per-instance technical detail for debug builds; [message] is the constant
 * user-facing string.
 *
 * [isRetryable] is a strict middleware contract — `false` for all subtypes because
 * mood failures require user action (correct input, retry with a valid name, etc.).
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`):
 * - [NotFound] / [BookNotFound] → 404
 * - [InvalidName] / [NameTooLong] → 400
 */
@Serializable
sealed interface MoodError : AppError {
    /**
     * No mood with the given id exists, or the mood has been soft-deleted.
     * Raised by [com.calypsan.listenup.api.MoodService.renameMood] and
     * [com.calypsan.listenup.api.MoodService.deleteMood].
     */
    @Serializable
    @SerialName("MoodError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : MoodError {
        override val message: String = "Mood not found."
        override val code: String = "MOOD_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * The mood name is empty, blank, or resolves to an empty slug after normalization
     * (i.e. contains only special characters). Raised by
     * [com.calypsan.listenup.api.MoodService.addMoodToBook] and
     * [com.calypsan.listenup.api.MoodService.renameMood].
     */
    @Serializable
    @SerialName("MoodError.InvalidName")
    data class InvalidName(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : MoodError {
        override val message: String = "Mood name is empty or contains only special characters."
        override val code: String = "MOOD_INVALID_NAME"
        override val isRetryable: Boolean = false
    }

    /**
     * The mood name exceeds the 64-character limit. Raised by
     * [com.calypsan.listenup.api.MoodService.addMoodToBook] and
     * [com.calypsan.listenup.api.MoodService.renameMood].
     */
    @Serializable
    @SerialName("MoodError.NameTooLong")
    data class NameTooLong(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : MoodError {
        override val message: String = "Mood name exceeds the 64-character limit."
        override val code: String = "MOOD_NAME_TOO_LONG"
        override val isRetryable: Boolean = false
    }

    /**
     * No book with the given id exists, or the book has been soft-deleted.
     * Raised by [com.calypsan.listenup.api.MoodService.addMoodToBook] when
     * the target book cannot be found.
     */
    @Serializable
    @SerialName("MoodError.BookNotFound")
    data class BookNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : MoodError {
        override val message: String = "Book not found."
        override val code: String = "MOOD_BOOK_NOT_FOUND"
        override val isRetryable: Boolean = false
    }
}
