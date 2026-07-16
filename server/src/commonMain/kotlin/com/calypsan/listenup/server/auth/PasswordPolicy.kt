package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.PASSWORD_MAX
import com.calypsan.listenup.api.dto.auth.PASSWORD_MIN
import com.calypsan.listenup.api.dto.auth.WeakPasswordReason
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult

/**
 * The one password-strength check register, setupRoot, claimInvite, and the profile
 * password change all share. Bounds mirror the existing [PASSWORD_MIN]/[PASSWORD_MAX]
 * `RegisterRequest`/`PasswordChange` DTOs already enforce structurally — this is the
 * graceful, typed counterpart raised as [AuthError.WeakPassword] rather than a bare
 * `IllegalArgumentException` from a DTO `init` block.
 */
object PasswordPolicy {
    /** Blank (including whitespace-only) fails before the length check — an all-space
     *  string can satisfy [PASSWORD_MIN] without being a real password. */
    fun validate(raw: String): AppResult<Unit> =
        when {
            raw.isBlank() -> AppResult.Failure(AuthError.WeakPassword(reason = WeakPasswordReason.BLANK))
            raw.length < PASSWORD_MIN -> AppResult.Failure(AuthError.WeakPassword(reason = WeakPasswordReason.TOO_SHORT))
            raw.length > PASSWORD_MAX -> AppResult.Failure(AuthError.WeakPassword(reason = WeakPasswordReason.TOO_LONG))
            else -> AppResult.Success(Unit)
        }
}
