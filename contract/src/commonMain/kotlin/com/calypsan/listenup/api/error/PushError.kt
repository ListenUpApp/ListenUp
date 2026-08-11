package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Errors from the push-notification surface. */
@Serializable
sealed interface PushError : AppError {
    /** Push is switched off (admin toggle) or no relay is configured. */
    @Serializable
    @SerialName("PushError.PushDisabled")
    data class PushDisabled(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : PushError {
        override val message: String = "Push notifications are not enabled on this server."
        override val code: String = "PUSH_DISABLED"
        override val isRetryable: Boolean = false
    }
}
