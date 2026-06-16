package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Login credentials plus optional flow modifiers.
 *
 * `sessionLabel` is an optional user-set label for the session ("My iPhone").
 * `timezone` is the device's IANA timezone identifier (e.g. "Europe/London"),
 * stored on the user row so the server can localise time-based display values.
 * Defaults to "UTC" so existing constructions and older clients remain valid.
 */
@Serializable
data class LoginRequest(
    @SerialName("email")
    val email: String,
    val password: String,
    val sessionLabel: String? = null,
    @SerialName("deviceInfo")
    val deviceInfo: DeviceInfo? = null,
    @SerialName("timezone")
    val timezone: String = "UTC",
) {
    init {
        require(password.length in PASSWORD_MIN..PASSWORD_MAX) {
            "password length out of bounds"
        }
    }
}

/** New-account registration. Display name defaults are set server-side. */
@Serializable
data class RegisterRequest(
    @SerialName("email")
    val email: String,
    val password: String,
    val displayName: String,
    val sessionLabel: String? = null,
    @SerialName("deviceInfo")
    val deviceInfo: DeviceInfo? = null,
) {
    init {
        require(password.length in PASSWORD_MIN..PASSWORD_MAX) {
            "password length out of bounds"
        }
        require(displayName.isNotBlank()) {
            "display name must not be blank"
        }
    }
}

/** Used to trade a refresh token for a new access/refresh pair. */
@Serializable
data class RefreshRequest(
    @SerialName("refreshToken")
    val refreshToken: RefreshToken,
)

const val PASSWORD_MIN = 8
const val PASSWORD_MAX = 1024
