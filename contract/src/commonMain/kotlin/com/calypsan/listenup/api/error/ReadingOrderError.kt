package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from reading-order operations exposed through `ReadingOrderService`.
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [com.calypsan.listenup.api.result.AppResult.Failure]. [correlationId] links
 * the server log line to the client's error display. [debugInfo] carries
 * per-instance technical detail for debug builds; [message] is the constant
 * user-facing string.
 *
 * [isRetryable] is `false` for all subtypes — reading-order failures require user
 * action (correct input, choose a different target, etc.).
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`):
 * - [NotFound] → 404
 * - [Forbidden] → 403
 * - [InvalidName] → 400
 */
@Serializable
sealed interface ReadingOrderError : AppError {
    /**
     * No reading order with the given id exists, or the reading order has been
     * soft-deleted, or the caller is a non-owner attempting to access a private
     * reading order. The two cases are intentionally merged to avoid leaking the
     * existence of private reading orders.
     */
    @Serializable
    @SerialName("ReadingOrderError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ReadingOrderError {
        override val message: String = "Reading order not found."
        override val code: String = "READING_ORDER_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * The caller does not have sufficient permission to perform the requested operation
     * on this reading order. Raised when a non-owner attempts to mutate another user's
     * reading order.
     */
    @Serializable
    @SerialName("ReadingOrderError.Forbidden")
    data class Forbidden(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ReadingOrderError {
        override val message: String = "You don't have permission to do that."
        override val code: String = "READING_ORDER_FORBIDDEN"
        override val isRetryable: Boolean = false
    }

    /**
     * The reading-order name failed validation (blank, too long, or otherwise invalid).
     * Raised by create and update operations when the name constraint is violated.
     */
    @Serializable
    @SerialName("ReadingOrderError.InvalidName")
    data class InvalidName(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ReadingOrderError {
        override val message: String = "Reading order name is invalid."
        override val code: String = "READING_ORDER_INVALID_NAME"
        override val isRetryable: Boolean = false
    }
}
