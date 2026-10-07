package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.SelectStoryWorldPermissionsLiveById
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/**
 * Per-operation permission gate for the per-user flags (`canEdit`, and the two Story World flags).
 *
 * ROOT and ADMIN implicitly hold every permission — they pass without a DB hit, so an
 * admin can never be locked out of a metadata edit. A MEMBER passes iff the
 * flag is set on a *live* (non-soft-deleted) row; a missing or tombstoned user
 * is denied.
 *
 * The check is a fresh DB lookup per call rather than reading the flags off the cached
 * [UserPrincipal]: a member whose `canEdit` is revoked must lose the ability on the next
 * operation, not only after their ≤15m access token expires. Denials reuse the existing
 * [AuthError.PermissionDenied] so the client folds a single "you can't do that" shape —
 * the policy never invents a new error variant.
 *
 * Returns `null` when the caller is allowed (so call sites read as
 * `requireCanEdit(...)?.let { return AppResult.Failure(it) }`); a non-null [AppError] is
 * the denial to surface.
 */
class UserPermissionPolicy(
    private val db: ListenUpDatabase,
) {
    /** Null when [userId]/[role] may edit content metadata; [AuthError.PermissionDenied] otherwise. */
    suspend fun requireCanEdit(
        userId: UserId,
        role: UserRole,
    ): AppError? {
        if (role == UserRole.ROOT || role == UserRole.ADMIN) return null
        // selectPermissionsLiveById already filters deleted_at IS NULL, so a tombstoned or absent
        // user yields no row → denied — matching the Exposed `takeIf { deletedAt == null }`.
        val granted =
            suspendTransaction(db) {
                db.usersQueries
                    .selectPermissionsLiveById(id = userId.value)
                    .executeAsOneOrNull()
                    ?.let { canEdit -> canEdit != 0L } ?: false
            }
        return if (granted) null else AuthError.PermissionDenied()
    }

    /**
     * Null when [userId]/[role] may create, edit and revert Story World content;
     * [AuthError.PermissionDenied] otherwise.
     */
    suspend fun requireCanContributeStoryWorld(
        userId: UserId,
        role: UserRole,
    ): AppError? = requireStoryWorld(userId, role) { it.can_contribute_story_world != 0L }

    /**
     * Null when [userId]/[role] may merge and delete Story World content;
     * [AuthError.PermissionDenied] otherwise.
     */
    suspend fun requireCanCurateStoryWorld(
        userId: UserId,
        role: UserRole,
    ): AppError? = requireStoryWorld(userId, role) { it.can_curate_story_world != 0L }

    /**
     * The shared shape of the two Story World gates: ROOT/ADMIN pass with no DB hit; a MEMBER needs a
     * live row with the flag.
     */
    private suspend fun requireStoryWorld(
        userId: UserId,
        role: UserRole,
        granted: (SelectStoryWorldPermissionsLiveById) -> Boolean,
    ): AppError? {
        if (role == UserRole.ROOT || role == UserRole.ADMIN) return null
        val allowed =
            suspendTransaction(db) {
                db.usersQueries
                    .selectStoryWorldPermissionsLiveById(id = userId.value)
                    .executeAsOneOrNull()
                    ?.let(granted) ?: false
            }
        return if (allowed) null else AuthError.PermissionDenied()
    }
}
