package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.server.db.UserEntity
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Per-operation permission gate for the per-user `canEdit`/`canShare` flags.
 *
 * ROOT and ADMIN implicitly hold every permission — they pass without a DB hit, so an
 * admin can never be locked out of a metadata edit or a share. A MEMBER passes iff the
 * specific flag is set on a *live* (non-soft-deleted) row; a missing or tombstoned user
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
    private val db: Database,
) {
    /** Null when [userId]/[role] may edit content metadata; [AuthError.PermissionDenied] otherwise. */
    suspend fun requireCanEdit(
        userId: UserId,
        role: UserRole,
    ): AppError? = require(userId, role) { it.canEdit }

    /** Null when [userId]/[role] may share a collection; [AuthError.PermissionDenied] otherwise. */
    suspend fun requireCanShare(
        userId: UserId,
        role: UserRole,
    ): AppError? = require(userId, role) { it.canShare }

    private suspend fun require(
        userId: UserId,
        role: UserRole,
        flag: (UserEntity) -> Boolean,
    ): AppError? {
        if (role == UserRole.ROOT || role == UserRole.ADMIN) return null
        val granted =
            suspendTransaction(db) {
                UserEntity.findById(userId.value)?.takeIf { it.deletedAt == null }?.let(flag) ?: false
            }
        return if (granted) null else AuthError.PermissionDenied()
    }
}
