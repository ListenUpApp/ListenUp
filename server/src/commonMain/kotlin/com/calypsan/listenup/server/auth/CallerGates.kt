package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError

/**
 * Null when the bound caller is ROOT or ADMIN; [AuthError.PermissionDenied] otherwise. A missing
 * caller is denied, not told its session expired: on the authed mount that can only be a server
 * wiring fault, and a client must not discard a good session over it.
 */
internal fun PrincipalProvider.requireAdmin(): AppError? {
    val caller = current() ?: return AuthError.PermissionDenied()
    return PermissionPolicy.requireAdmin(caller)
}

/** Null when the bound caller holds [permission] under [policy]; [AuthError.PermissionDenied] otherwise. */
internal suspend fun PrincipalProvider.requirePermission(
    policy: PermissionPolicy,
    permission: Permission,
): AppError? {
    val caller = current() ?: return AuthError.PermissionDenied()
    return policy.require(caller, permission)
}
