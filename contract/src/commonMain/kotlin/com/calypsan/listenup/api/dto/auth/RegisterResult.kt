package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Outcome of register(). Polymorphic because closed-with-approval-queue
 * instances do not return a session immediately.
 */
@Serializable
sealed interface RegisterResult {
    /** Registered AND logged in (open registration). */
    @Serializable
    @SerialName("RegisterResult.Authenticated")
    data class Authenticated(
        val session: AuthSession,
    ) : RegisterResult

    /**
     * Account created in PENDING_APPROVAL; admin must approve before login.
     *
     * Carries the server-issued `userId` so the client can subscribe to the
     * registration-status RPC watch (keyed by user id) and prompt re-login
     * when the account is approved.
     */
    @Serializable
    @SerialName("RegisterResult.PendingApproval")
    data class PendingApproval(
        val userId: UserId,
    ) : RegisterResult
}
