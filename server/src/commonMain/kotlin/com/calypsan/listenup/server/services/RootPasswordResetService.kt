@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.Argon2Limiter
import com.calypsan.listenup.server.auth.PasswordPolicy
import com.calypsan.listenup.server.auth.RootResetToken
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * Root's escape hatch: resets root's password against the one-time token
 * [RootResetToken] arms at server startup.
 *
 * Root has no admin above them, so [PasswordResetService]'s admin-approval flow cannot rescue
 * them — this is a genuinely different authorisation model (host access, proven by the
 * startup-logged token) rather than a variant of the member flow, so it is kept as its own
 * service instead of a method on [PasswordResetService] (already 495 lines).
 * [com.calypsan.listenup.server.auth.AuthServiceImpl] (681 lines) delegates to it in one line,
 * the same shape as its other three reset methods.
 */
class RootPasswordResetService(
    private val db: ListenUpDatabase,
    private val passwords: Argon2Limiter,
    private val sessions: SessionRevoker,
    private val clock: Clock,
    private val rootResetToken: RootResetToken,
) {
    /**
     * Resets root's password against [token].
     *
     * Unarmed, expired, already-consumed, and simply-wrong [token]s all return the identical
     * [AuthError.RootResetUnavailable] — deliberately, so an unauthenticated caller cannot learn
     * whether the hatch is open, whether it has already been used, or whether they merely typed
     * the token wrong.
     *
     * The token is consumed **before** [newPassword] is validated. Validating first would let a
     * caller distinguish a valid token from an invalid one by which error comes back — a
     * weak-password error only ever follows a real token check — reopening exactly the
     * distinction [AuthError.RootResetUnavailable]'s single shape exists to hide. The trade-off:
     * a caller who submits a weak password on an otherwise-valid attempt burns the one-time
     * token anyway and must wait for the next boot to re-arm the hatch.
     *
     * On success every session for root is revoked — the same never-leave-an-intruder-signed-in
     * guarantee [PasswordResetService.complete] makes for a member reset.
     */
    suspend fun resetRoot(
        token: String,
        newPassword: String,
    ): AppResult<Unit> {
        if (!rootResetToken.consume(token, clock.now())) {
            return AppResult.Failure(AuthError.RootResetUnavailable())
        }
        PasswordPolicy.validate(newPassword).let { if (it is AppResult.Failure) return it }

        val rootId =
            db.usersQueries.selectFirstByRole(UserRoleColumn.ROOT.name).executeAsOneOrNull()
                ?: return AppResult.Failure(AuthError.RootResetUnavailable())

        val hash = passwords.hash(newPassword)
        val now = clock.now().toEpochMilliseconds()
        suspendTransaction(db) {
            db.usersQueries.updatePasswordHashAt(password_hash = hash, updated_at = now, id = rootId)
        }
        sessions.revokeAll(UserId(rootId))
        return AppResult.Success(Unit)
    }
}
