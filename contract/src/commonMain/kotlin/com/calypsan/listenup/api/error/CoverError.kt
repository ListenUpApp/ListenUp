package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from book cover operations exposed through
 * [com.calypsan.listenup.api.BookService].
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [com.calypsan.listenup.api.result.AppResult.Failure]. [correlationId] links
 * the server log line to the client's error display. [debugInfo] carries
 * per-instance technical detail for debug builds; [message] is the constant
 * user-facing string.
 *
 * [isRetryable] is `false` — cover failures require user action.
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`):
 * - [NotPresent] → 404
 */
@Serializable
sealed interface CoverError : AppError {
    /**
     * The book has no cover stored on the server.
     * Raised by [com.calypsan.listenup.api.BookService.deleteBookCover] when
     * there is nothing to delete.
     */
    @Serializable
    @SerialName("CoverError.NotPresent")
    data class NotPresent(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : CoverError {
        override val message: String = "This book doesn't have a cover to delete."
        override val code: String = "COVER_NOT_PRESENT"
        override val isRetryable: Boolean = false
    }
}
