package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from [com.calypsan.listenup.api.ReadingOrderService] (#962).
 *
 * [isRetryable] is `false` for every subtype — each needs the user to change something. A member with no
 * permission to make reading orders at all gets [AuthError.PermissionDenied] instead, the shape every
 * permission gate returns.
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`):
 * - [NotFound] → 404
 * - [Forbidden] → 403
 * - [NameAlreadyExists] → 409
 * - [InvalidName], [BookOutsideSeries], [ChoiceUnavailable], [InvalidInput] → 400
 */
@Serializable
sealed interface ReadingOrderError : AppError {
    /**
     * The order is missing or deleted, its series is not live, or a named book is missing or not visible to
     * the caller — merged so an inaccessible book's existence never leaks.
     */
    @Serializable
    @SerialName("ReadingOrderError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ReadingOrderError {
        override val message: String = "That reading order no longer exists."
        override val code: String = "READING_ORDER_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * The caller is neither an admin nor the order's maker, or is its maker but no longer holds the
     * permission to make reading orders.
     */
    @Serializable
    @SerialName("ReadingOrderError.Forbidden")
    data class Forbidden(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ReadingOrderError {
        override val message: String = "Only the person who made this reading order, or an admin, can change it."
        override val code: String = "READING_ORDER_FORBIDDEN"
        override val isRetryable: Boolean = false
    }

    /**
     * The name is blank or longer than 80 characters after trimming.
     */
    @Serializable
    @SerialName("ReadingOrderError.InvalidName")
    data class InvalidName(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ReadingOrderError {
        override val message: String = "Reading order names need 1 to 80 characters."
        override val code: String = "READING_ORDER_INVALID_NAME"
        override val isRetryable: Boolean = false
    }

    /**
     * The series already has a live reading order whose name normalizes the same way.
     */
    @Serializable
    @SerialName("ReadingOrderError.NameAlreadyExists")
    data class NameAlreadyExists(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ReadingOrderError {
        override val message: String = "This series already has a reading order with that name."
        override val code: String = "READING_ORDER_NAME_EXISTS"
        override val isRetryable: Boolean = false
    }

    /**
     * The book is visible but belongs to no series in the order's subtree.
     */
    @Serializable
    @SerialName("ReadingOrderError.BookOutsideSeries")
    data class BookOutsideSeries(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ReadingOrderError {
        override val message: String = "Only books in this series and its sub-series can be added."
        override val code: String = "READING_ORDER_BOOK_OUTSIDE_SERIES"
        override val isRetryable: Boolean = false
    }

    /**
     * The followed order is missing, or lives on a series that is neither this series nor one of its
     * ancestors.
     */
    @Serializable
    @SerialName("ReadingOrderError.ChoiceUnavailable")
    data class ChoiceUnavailable(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ReadingOrderError {
        override val message: String = "That reading order isn't available for this series."
        override val code: String = "READING_ORDER_CHOICE_UNAVAILABLE"
        override val isRetryable: Boolean = false
    }

    /**
     * A request the UI never sends, such as a reorder naming more than the per-call cap of book ids.
     */
    @Serializable
    @SerialName("ReadingOrderError.InvalidInput")
    data class InvalidInput(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ReadingOrderError {
        override val message: String = "That change couldn't be made to the reading order."
        override val code: String = "READING_ORDER_INVALID_INPUT"
        override val isRetryable: Boolean = false
    }
}
