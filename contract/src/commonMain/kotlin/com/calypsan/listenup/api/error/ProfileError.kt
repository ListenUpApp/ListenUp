package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Profile-domain failures crossing the wire as typed values.
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [com.calypsan.listenup.api.result.AppResult.Failure]. [correlationId] links
 * the server log line to the client's error display. [debugInfo] carries
 * per-instance technical detail for debug builds; [message] is the constant
 * user-facing string.
 *
 * [isRetryable] is `false` for all subtypes — profile failures require user
 * action (use a valid image, enter the correct password, fix input).
 */
@Serializable
sealed interface ProfileError : AppError {
    /**
     * The uploaded avatar image could not be used.
     * Raised when an image fails format validation (type, size, or decode error).
     */
    @Serializable
    @SerialName("ProfileError.InvalidImage")
    data class InvalidImage(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ProfileError {
        override val message: String = "That image couldn't be used. Use a JPEG, PNG, or WebP under the size limit."
        override val code: String = "PROFILE_INVALID_IMAGE"
        override val isRetryable: Boolean = false
    }

    /**
     * The provided current password did not match the stored hash.
     * Raised when a password change is requested with an incorrect current password.
     */
    @Serializable
    @SerialName("ProfileError.WrongPassword")
    data class WrongPassword(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : ProfileError {
        override val message: String = "Your current password is incorrect."
        override val code: String = "PROFILE_WRONG_PASSWORD"
        override val isRetryable: Boolean = false
    }
}
