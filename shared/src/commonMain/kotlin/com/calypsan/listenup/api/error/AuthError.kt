package com.calypsan.listenup.api.error

import com.calypsan.listenup.api.dto.auth.WeakPasswordReason
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
    data class InvalidCredentials(
        override val correlationId: String? = null,
    ) : AuthError

    /** The email address is already registered. Returned by register() and setupRoot(). */
    @Serializable
    data class EmailAlreadyExists(
        override val correlationId: String? = null,
    ) : AuthError

    /** Instance has registration closed and no approval queue. */
    @Serializable
    data class RegistrationDisabled(
        override val correlationId: String? = null,
    ) : AuthError

    /** Instance has zero users; caller must use setupRoot, not register. */
    @Serializable
    data class SetupRequired(
        override val correlationId: String? = null,
    ) : AuthError

    /** setupRoot called against an instance that already has users. */
    @Serializable
    data class SetupAlreadyComplete(
        override val correlationId: String? = null,
    ) : AuthError

    /** Login attempted against a PENDING_APPROVAL account without a redemption token. */
    @Serializable
    data class PendingApproval(
        override val correlationId: String? = null,
    ) : AuthError

    /** Account is in DENIED status. */
    @Serializable
    data class AccountDenied(
        override val correlationId: String? = null,
    ) : AuthError

    /** Access JWT is past expiry or its session row is revoked. */
    @Serializable
    data class SessionExpired(
        override val correlationId: String? = null,
    ) : AuthError

    /** JWT decoded fine but no matching session row exists. */
    @Serializable
    data class SessionNotFound(
        override val correlationId: String? = null,
    ) : AuthError

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
    data class InvalidRefreshToken(
        val familyRevoked: Boolean,
        override val correlationId: String? = null,
    ) : AuthError

    /**
     * Rate limit hit on this endpoint. `retryAfterSeconds` maps directly from
     * the server's `Retry-After` header; clients should surface it to the user.
     */
    @Serializable
    data class RateLimited(
        val retryAfterSeconds: Int,
        override val correlationId: String? = null,
    ) : AuthError

    /** Password failed policy. `reason` names the specific violation; see WeakPasswordReason. */
    @Serializable
    data class WeakPassword(
        val reason: WeakPasswordReason,
        override val correlationId: String? = null,
    ) : AuthError

    /** Authenticated caller lacks permission for an admin-only operation. */
    @Serializable
    data class PermissionDenied(
        override val correlationId: String? = null,
    ) : AuthError
}
