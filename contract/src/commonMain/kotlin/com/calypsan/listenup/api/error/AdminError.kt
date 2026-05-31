package com.calypsan.listenup.api.error

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed failures from admin user-management operations exposed through `AdminService`.
 *
 * Every subtype is `@Serializable` so it can cross the RPC wire as a typed
 * [com.calypsan.listenup.api.result.AppResult.Failure]. [correlationId] links
 * the server log line to the client's error display. [debugInfo] carries
 * per-instance technical detail for debug builds; [message] is the constant
 * user-facing string.
 *
 * [isRetryable] is `false` for all subtypes — admin failures require user
 * action (correct input, choose a different target user, etc.).
 *
 * Authorization failures are *not* modelled here: enforcement reuses
 * [AuthError.PermissionDenied] so the client folds a single "you can't do that"
 * shape regardless of which surface raised it.
 *
 * HTTP status mapping (wired in `AppErrorStatusPages.kt`):
 * - [UserNotFound] → 404
 * - [CannotModifyRoot] / [CannotDemoteLastAdmin] / [CannotDeleteSelf] / [CannotDeleteLastAdmin] → 409
 * - [InvalidInput] → 400
 */
@Serializable
sealed interface AdminError : AppError {
    /**
     * No user with the given id exists.
     * Raised when an admin operation targets a user account that cannot be found.
     */
    @Serializable
    @SerialName("AdminError.UserNotFound")
    data class UserNotFound(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AdminError {
        override val message: String = "That user could not be found."
        override val code: String = "ADMIN_USER_NOT_FOUND"
        override val isRetryable: Boolean = false
    }

    /**
     * The caller attempted to modify or delete the root account.
     * The root account is the bootstrap administrator and is immutable.
     */
    @Serializable
    @SerialName("AdminError.CannotModifyRoot")
    data class CannotModifyRoot(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AdminError {
        override val message: String = "The root account cannot be modified or deleted."
        override val code: String = "ADMIN_CANNOT_MODIFY_ROOT"
        override val isRetryable: Boolean = false
    }

    /**
     * The caller attempted to demote the last remaining admin to a non-admin role.
     * At least one admin must always exist so the system stays manageable.
     */
    @Serializable
    @SerialName("AdminError.CannotDemoteLastAdmin")
    data class CannotDemoteLastAdmin(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AdminError {
        override val message: String = "At least one admin must remain."
        override val code: String = "ADMIN_CANNOT_DEMOTE_LAST_ADMIN"
        override val isRetryable: Boolean = false
    }

    /**
     * The caller attempted to delete their own account through the admin surface.
     * Self-deletion is not allowed here; it routes through account settings instead.
     */
    @Serializable
    @SerialName("AdminError.CannotDeleteSelf")
    data class CannotDeleteSelf(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AdminError {
        override val message: String = "You cannot delete your own account here."
        override val code: String = "ADMIN_CANNOT_DELETE_SELF"
        override val isRetryable: Boolean = false
    }

    /**
     * The caller attempted to delete the last remaining admin account.
     * At least one admin must always exist so the system stays manageable.
     */
    @Serializable
    @SerialName("AdminError.CannotDeleteLastAdmin")
    data class CannotDeleteLastAdmin(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AdminError {
        override val message: String = "At least one admin must remain."
        override val code: String = "ADMIN_CANNOT_DELETE_LAST_ADMIN"
        override val isRetryable: Boolean = false
    }

    /**
     * The request body failed validation (e.g. unknown role, malformed field).
     * Raised by admin operations when input constraints are violated.
     */
    @Serializable
    @SerialName("AdminError.InvalidInput")
    data class InvalidInput(
        override val correlationId: String? = null,
        override val debugInfo: String? = null,
    ) : AdminError {
        override val message: String = "The request was invalid."
        override val code: String = "ADMIN_INVALID_INPUT"
        override val isRetryable: Boolean = false
    }
}
