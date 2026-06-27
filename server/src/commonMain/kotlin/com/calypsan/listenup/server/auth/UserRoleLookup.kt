package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/**
 * Resolves a user id to its [UserRole] via a single indexed primary-key lookup.
 *
 * The signed audio URL proves *who* the caller is (`u=<userId>` + HMAC) but not
 * their *role* — the signed payload deliberately omits it so a role change takes
 * effect without re-minting every live URL. The audio route therefore resolves
 * the role here, then gates through
 * [com.calypsan.listenup.server.api.BookAccessPolicy].
 *
 * Returns `null` when no user row exists for [userId] — a signature that names a
 * deleted or unknown user, which the route treats as a 404.
 */
internal class UserRoleLookup(
    private val db: ListenUpDatabase,
) {
    suspend fun roleOf(userId: String): UserRole? =
        suspendTransaction(db) {
            db.usersQueries
                .selectRoleById(id = userId)
                .executeAsOneOrNull()
                ?.let { UserRoleColumn.valueOf(it).toContract() }
        }
}
