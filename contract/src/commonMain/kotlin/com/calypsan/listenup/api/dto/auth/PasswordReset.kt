package com.calypsan.listenup.api.dto.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Handle for an open password-reset request. Returned for *every* call to
 * `requestPasswordReset`, including addresses with no account behind them — the shape must
 * not reveal whether the account exists.
 */
@Serializable
@SerialName("PasswordResetTicket")
data class PasswordResetTicket(
    @SerialName("ticketId")
    val ticketId: String,
    @SerialName("expiresAt")
    val expiresAt: Long,
)

/** Lifecycle state of a reset request. `APPROVED` is the only state completion accepts. */
@Serializable
enum class PasswordResetStatus {
    @SerialName("PENDING")
    PENDING,

    @SerialName("APPROVED")
    APPROVED,

    @SerialName("DENIED")
    DENIED,

    @SerialName("CONSUMED")
    CONSUMED,

    @SerialName("EXPIRED")
    EXPIRED,
}

/** Wire payload for `AuthServicePublic.observePasswordResetStatus`. */
@Serializable
@SerialName("PasswordResetStatusEvent")
data class PasswordResetStatusEvent(
    @SerialName("status")
    val status: PasswordResetStatus,
    @SerialName("expiresAt")
    val expiresAt: Long,
)

/**
 * One pending reset as the admin sees it. Deliberately carries **no code** — the code is
 * returned only from the decision call, and never from a list.
 */
@Serializable
@SerialName("PasswordResetRequest")
data class PasswordResetRequest(
    @SerialName("id")
    val id: String,
    @SerialName("userId")
    val userId: UserId,
    @SerialName("displayName")
    val displayName: String,
    @SerialName("email")
    val email: String,
    @SerialName("requestedAt")
    val requestedAt: Long,
    @SerialName("expiresAt")
    val expiresAt: Long,
)

/** Result of an admin decision on a reset request. */
@Serializable
sealed interface PasswordResetDecisionOutcome {
    /**
     * Approved. [code] is for the admin to convey to the requester through a channel they
     * already trust. It appears in this response and nowhere else — never in a list, never
     * in a push payload, never in a log.
     */
    @Serializable
    @SerialName("PasswordResetDecisionOutcome.Approved")
    data class Approved(
        @SerialName("code")
        val code: String,
    ) : PasswordResetDecisionOutcome

    /** Denied. The request is dead; the requester must start over. */
    @Serializable
    @SerialName("PasswordResetDecisionOutcome.Denied")
    data object Denied : PasswordResetDecisionOutcome
}
