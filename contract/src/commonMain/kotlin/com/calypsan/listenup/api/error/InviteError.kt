package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from invite-based registration operations exposed through `InviteService`.
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [com.calypsan.listenup.api.result.AppResult.Failure]. [correlationId] links
 * the server log line to the client's error display. [debugInfo] carries
 * per-instance technical detail for debug builds; [message] is the constant
 * user-facing string.
 *
 * [isRetryable] is `false` for all subtypes — invite failures require user
 * action (request a fresh invite, use a different email, correct input).
 *
 * Authorization failures are *not* modelled here: enforcement reuses
 * [AuthError.PermissionDenied] so the client folds a single "you can't do that"
 * shape regardless of which surface raised it.
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`):
 * - [NotFound] → 404
 * - [Expired] / [AlreadyClaimed] / [EmailInUse] → 409
 * - [InvalidInput] → 400
 */
@Serializable
sealed interface InviteError : AppError {
    /**
     * No invite with the given id or token exists.
     * Raised when an invite operation targets an invite that cannot be found.
     */
    @Serializable
    @SerialName("InviteError.NotFound")
    data class NotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : InviteError {
        override val message: String = "That invite could not be found."
        override val code: String = "INVITE_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * The invite's expiry time has passed.
     * Raised when claiming an invite that is no longer valid.
     */
    @Serializable
    @SerialName("InviteError.Expired")
    data class Expired(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : InviteError {
        override val message: String = "This invite has expired."
        override val code: String = "INVITE_EXPIRED"
        override val isRetryable: Boolean = false
    }

    /**
     * The invite has already been claimed by a registered account.
     * Each invite is single-use; a claimed invite cannot be reused.
     */
    @Serializable
    @SerialName("InviteError.AlreadyClaimed")
    data class AlreadyClaimed(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : InviteError {
        override val message: String = "This invite has already been used."
        override val code: String = "INVITE_ALREADY_CLAIMED"
        override val isRetryable: Boolean = false
    }

    /**
     * An account already exists for the email being registered.
     * Raised when claiming an invite with an email that is already in use.
     */
    @Serializable
    @SerialName("InviteError.EmailInUse")
    data class EmailInUse(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : InviteError {
        override val message: String = "An account already exists for this email."
        override val code: String = "INVITE_EMAIL_IN_USE"
        override val isRetryable: Boolean = false
    }

    /**
     * The request body failed validation (e.g. malformed email, missing field).
     * Raised by invite operations when input constraints are violated.
     */
    @Serializable
    @SerialName("InviteError.InvalidInput")
    data class InvalidInput(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : InviteError {
        override val message: String = "The request was invalid."
        override val code: String = "INVITE_INVALID_INPUT"
        override val isRetryable: Boolean = false
    }
}
