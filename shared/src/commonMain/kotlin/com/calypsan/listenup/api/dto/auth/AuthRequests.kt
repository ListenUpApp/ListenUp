package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.Serializable

/**
 * Login credentials plus optional flow modifiers.
 *
 * `pendingRegistrationToken` is set when the user is redeeming an admin-approved
 * registration — server validates and activates the account in the same call.
 *
 * `sessionLabel` is an optional user-set label for the session ("My iPhone").
 */
@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val pendingRegistrationToken: PendingRegistrationToken? = null,
    val sessionLabel: String? = null,
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
    val email: String,
    val password: String,
    val displayName: String,
    val sessionLabel: String? = null,
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
data class RefreshRequest(val refreshToken: RefreshToken)

const val PASSWORD_MIN = 8
const val PASSWORD_MAX = 1024
