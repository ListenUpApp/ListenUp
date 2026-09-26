package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Errors from the Hardcover integration surface ([com.calypsan.listenup.api.HardcoverService]).
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`):
 * - [NotConfigured] → 503
 * - [Unavailable] → 503
 * - [AlreadyConnected] → 409
 */
@Serializable
sealed interface HardcoverError : AppError {
    /** This server has no Hardcover app registered (no client id), so connecting is not possible. */
    @Serializable
    @SerialName("HardcoverError.NotConfigured")
    data class NotConfigured(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : HardcoverError {
        override val message: String = "Hardcover isn't set up on this server."
        override val code: String = "HARDCOVER_NOT_CONFIGURED"
        override val isRetryable: Boolean = false
    }

    /** Hardcover could not be reached, or answered with a temporary failure. Safe to retry. */
    @Serializable
    @SerialName("HardcoverError.Unavailable")
    data class Unavailable(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : HardcoverError {
        override val message: String = "Hardcover can't be reached right now. Try again shortly."
        override val code: String = "HARDCOVER_UNAVAILABLE"
        override val isRetryable: Boolean = true
    }

    /** The user is already connected; disconnect first to connect a different account. */
    @Serializable
    @SerialName("HardcoverError.AlreadyConnected")
    data class AlreadyConnected(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : HardcoverError {
        override val message: String = "You're already connected to Hardcover."
        override val code: String = "HARDCOVER_ALREADY_CONNECTED"
        override val isRetryable: Boolean = false
    }
}
