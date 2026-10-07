package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.api.dto.auth.allows
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/**
 * The one permission gate. Roles set the ceiling: ROOT and ADMIN hold every [Permission] and pass
 * without a database hit, so an admin is never locked out. A MEMBER holds exactly the flags on their
 * *live* (non-soft-deleted) row; a missing or tombstoned user holds nothing.
 *
 * [require] reads the row fresh on every call rather than trusting the cached [UserPrincipal]: a
 * member whose flag is revoked loses the power on the next operation, not when their ≤15m access
 * token expires. Every denial is [AuthError.PermissionDenied] — the single "you can't do that" shape
 * clients fold.
 *
 * Both functions return null when the caller is allowed, so call sites read
 * `require(caller, Permission.X)?.let { return AppResult.Failure(it) }`.
 */
class PermissionPolicy(
    private val db: ListenUpDatabase,
) {
    /** Null when [principal] holds [permission]; [AuthError.PermissionDenied] otherwise. */
    suspend fun require(
        principal: UserPrincipal,
        permission: Permission,
    ): AppError? {
        if (principal.role.isAdmin()) return null
        val granted =
            suspendTransaction(db) {
                db.usersQueries
                    .selectPermissionFlagsLiveById(id = principal.userId.value) {
                        canEdit,
                        canCurateLibrary,
                        canContributeStoryWorld,
                        canCurateStoryWorld,
                        canMakeReadingOrders,
                        ->
                        UserPermissions(
                            canEditMetadata = canEdit != 0L,
                            canCurateLibrary = canCurateLibrary != 0L,
                            canContributeStoryWorld = canContributeStoryWorld != 0L,
                            canCurateStoryWorld = canCurateStoryWorld != 0L,
                            canMakeReadingOrders = canMakeReadingOrders != 0L,
                        )
                    }.executeAsOneOrNull()
                    ?.allows(permission) ?: false
            }
        return if (granted) null else AuthError.PermissionDenied()
    }

    /** The admin-only gate, which needs no lookup: the role is on the principal. */
    companion object {
        /** Null when [principal] is ROOT or ADMIN; [AuthError.PermissionDenied] otherwise. */
        fun requireAdmin(principal: UserPrincipal): AppError? =
            if (principal.role.isAdmin()) null else AuthError.PermissionDenied()
    }
}
