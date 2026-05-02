package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.Serializable

/**
 * Outcome of register(). Polymorphic because closed-with-approval-queue
 * instances do not return a session immediately.
 */
@Serializable
sealed interface RegisterResult {
    /** Registered AND logged in (open registration). */
    @Serializable
    data class Authenticated(
        val session: AuthSession,
    ) : RegisterResult

    /** Account created in PENDING_APPROVAL; admin must approve before login. */
    @Serializable
    data object PendingApproval : RegisterResult
}
