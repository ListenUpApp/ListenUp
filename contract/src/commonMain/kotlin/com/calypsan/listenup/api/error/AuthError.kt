package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.dto.auth.WeakPasswordReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Auth-domain error variants. The server emits these as typed values across
 * the wire (RPC and REST). The client matches them via exhaustive `when`;
 * never via string matching.
 */
@Serializable
sealed interface AuthError : AppError {
    /** Email or password did not match. Deliberately non-specific to prevent account enumeration. */
    @Serializable
    @SerialName("AuthError.InvalidCredentials")
    data class InvalidCredentials(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AuthError {
        override val message: String = "Email or password did not match."
        override val code: String = "AUTH_INVALID_CREDENTIALS"
        override val isRetryable: Boolean = false
    }

    /** The email address is already registered. Returned by register() and setupRoot(). */
    @Serializable
    @SerialName("AuthError.EmailAlreadyExists")
    data class EmailAlreadyExists(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AuthError {
        override val message: String = "That email is already registered."
        override val code: String = "AUTH_EMAIL_ALREADY_EXISTS"
        override val isRetryable: Boolean = false
    }

    /** Instance has registration closed and no approval queue. */
    @Serializable
    @SerialName("AuthError.RegistrationDisabled")
    data class RegistrationDisabled(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AuthError {
        override val message: String = "This server is not accepting new registrations."
        override val code: String = "AUTH_REGISTRATION_DISABLED"
        override val isRetryable: Boolean = false
    }

    /** Instance has zero users; caller must use setupRoot, not register. */
    @Serializable
    @SerialName("AuthError.SetupRequired")
    data class SetupRequired(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AuthError {
        override val message: String = "This server has not been initialized yet."
        override val code: String = "AUTH_SETUP_REQUIRED"
        override val isRetryable: Boolean = false
    }

    /** setupRoot called against an instance that already has users. */
    @Serializable
    @SerialName("AuthError.SetupAlreadyComplete")
    data class SetupAlreadyComplete(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AuthError {
        override val message: String = "Setup has already been completed."
        override val code: String = "AUTH_SETUP_ALREADY_COMPLETE"
        override val isRetryable: Boolean = false
    }

    /** Login attempted against a PENDING_APPROVAL account without a redemption token. */
    @Serializable
    @SerialName("AuthError.PendingApproval")
    data class PendingApproval(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AuthError {
        override val message: String = "Your account is awaiting administrator approval."
        override val code: String = "AUTH_PENDING_APPROVAL"
        override val isRetryable: Boolean = false
    }

    /** Account is in DENIED status. */
    @Serializable
    @SerialName("AuthError.AccountDenied")
    data class AccountDenied(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AuthError {
        override val message: String = "Your account has been denied access."
        override val code: String = "AUTH_ACCOUNT_DENIED"
        override val isRetryable: Boolean = false
    }

    /** Access JWT is past expiry or its session row is revoked. */
    @Serializable
    @SerialName("AuthError.SessionExpired")
    data class SessionExpired(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AuthError {
        override val message: String = "Your session expired. Please sign in again."
        override val code: String = "AUTH_SESSION_EXPIRED"
        override val isRetryable: Boolean = false
    }

    /**
     * The server we reconnected to is a *different* instance than the one this
     * session was issued for (its persisted `instanceId` changed — DB recreated,
     * or a different server at the same URL). The session cannot be reused.
     */
    @Serializable
    @SerialName("AuthError.ServerInstanceChanged")
    data class ServerInstanceChanged(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AuthError {
        override val message: String = "This server was reset or replaced. Please sign in again."
        override val code: String = "AUTH_SERVER_INSTANCE_CHANGED"
        override val isRetryable: Boolean = false
    }

    /** JWT decoded fine but no matching session row exists. */
    @Serializable
    @SerialName("AuthError.SessionNotFound")
    data class SessionNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AuthError {
        override val message: String = "You're signed out. Please sign in again."
        override val code: String = "AUTH_SESSION_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * Refresh token did not match the session's current hash.
     *
     * `familyRevoked = true` means a replay was detected and the entire family
     * has been revoked — every device using this login is now logged out and
     * the user should re-authenticate everywhere.
     *
     * `familyRevoked = false` means the token was unrecognised (stale, junk,
     * or revoked previously) — the user only needs to re-auth on this device.
     */
    @Serializable
    @SerialName("AuthError.InvalidRefreshToken")
    data class InvalidRefreshToken(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val familyRevoked: Boolean,
    ) : AuthError {
        override val message: String = "Your session is no longer valid. Please sign in again."
        override val code: String = "AUTH_INVALID_REFRESH_TOKEN"
        override val isRetryable: Boolean = false
    }

    /**
     * Rate limit hit on this endpoint. `retryAfterSeconds` maps directly from
     * the server's `Retry-After` header; clients should surface it to the user.
     */
    @Serializable
    @SerialName("AuthError.RateLimited")
    data class RateLimited(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val retryAfterSeconds: Int,
    ) : AuthError {
        override val message: String = "Too many attempts. Try again later."
        override val code: String = "AUTH_RATE_LIMITED"
        override val isRetryable: Boolean = true
    }

    /** Password failed policy. `reason` names the specific violation; see WeakPasswordReason. */
    @Serializable
    @SerialName("AuthError.WeakPassword")
    data class WeakPassword(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
        val reason: WeakPasswordReason,
    ) : AuthError {
        override val message: String = "Password does not meet requirements."
        override val code: String = "AUTH_WEAK_PASSWORD"
        override val isRetryable: Boolean = false
    }

    /** Authenticated caller lacks permission for an admin-only operation. */
    @Serializable
    @SerialName("AuthError.PermissionDenied")
    data class PermissionDenied(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AuthError {
        override val message: String = "You don't have permission to perform this action."
        override val code: String = "AUTH_PERMISSION_DENIED"
        override val isRetryable: Boolean = false
    }

    /** No registration exists for the given user id — a stale, tampered, or malformed id. */
    @Serializable
    @SerialName("AuthError.RegistrationNotFound")
    data class RegistrationNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AuthError {
        override val message: String = "That registration could not be found."
        override val code: String = "AUTH_REGISTRATION_NOT_FOUND"
        override val isRetryable: Boolean = false
    }
}
