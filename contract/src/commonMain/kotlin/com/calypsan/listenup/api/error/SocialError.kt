package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Social-surface failures (currently-listening / book-readers). */
@Serializable
sealed interface SocialError : AppError {
    /**
     * The requested resource is unavailable to the caller — used both for an absent/inaccessible
     * book (never revealing which) and for a missing principal.
     */
    @Serializable
    @SerialName("SocialError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : SocialError {
        override val message: String = "That isn't available."
        override val code: String = "SOCIAL_NOT_FOUND"
        override val isRetryable: Boolean = false
    }
}
