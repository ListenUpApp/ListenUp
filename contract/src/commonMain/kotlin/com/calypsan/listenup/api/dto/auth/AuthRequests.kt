package com.calypsan.listenup.api.dto.auth

import com.calypsan.listenup.api.dto.profile.UpdateProfileRequest
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
        require(email.length <= EMAIL_MAX) { "email too long" }
        require(sessionLabel == null || sessionLabel.length <= SESSION_LABEL_MAX) { "sessionLabel too long" }
        require(timezone.length <= TIMEZONE_MAX) { "timezone too long" }
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
        require(email.length <= EMAIL_MAX) { "email too long" }
        require(displayName.length <= UpdateProfileRequest.MAX_DISPLAY_NAME) { "displayName too long" }
        require(sessionLabel == null || sessionLabel.length <= SESSION_LABEL_MAX) { "sessionLabel too long" }
    }
}

/** Used to trade a refresh token for a new access/refresh pair. */
@Serializable
data class RefreshRequest(
    @SerialName("refreshToken")
    val refreshToken: RefreshToken,
    /**
     * The app version the device runs now. Optional and ignored by servers that predate it
     * (`contractJson` ignores unknown keys), so an updated client can still refresh against them.
     */
    @SerialName("clientVersion")
    val clientVersion: String? = null,
)

const val PASSWORD_MIN = 8
const val PASSWORD_MAX = 1024

/** Longest email address the boundary accepts (the RFC 5321 maximum); anonymous callers reach these fields. */
const val EMAIL_MAX = 254

/** Longest session label the boundary accepts. */
const val SESSION_LABEL_MAX = 128

/** Longest IANA timezone identifier the boundary accepts. */
const val TIMEZONE_MAX = 64
