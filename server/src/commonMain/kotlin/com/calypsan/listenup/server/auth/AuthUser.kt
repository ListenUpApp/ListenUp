package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserPermissions
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.UserStatusColumn
import com.calypsan.listenup.server.db.sqldelight.Users

/**
 * A server-side projection of a `users` row — the SQLDelight equivalent of the (still-present, soon-
 * to-be-deleted) Exposed `UserEntity`. The auth repos read rows into this immutable value so the
 * service layer never touches a transport- or ORM-shaped type: [SessionIssuer], [AuthServiceImpl],
 * and the invite path all speak [AuthUser].
 *
 * Enum columns are stored by name in SQLite (the same form the Exposed `enumerationByName` columns
 * used), and mapped name → enum here. The role / status `CHECK` constraints guarantee the stored
 * strings are always valid members, so [UserRoleColumn.valueOf] / [UserStatusColumn.valueOf] cannot
 * throw on a well-formed row. The Argon2id `password_hash` is carried verbatim — never re-derived.
 */
internal data class AuthUser(
    val id: String,
    val email: String,
    val emailNormalized: String,
    val passwordHash: String,
    val role: UserRoleColumn,
    val displayName: String,
    val status: UserStatusColumn,
    val createdAt: Long,
    val canEdit: Boolean,
    val canShare: Boolean,
    val approvedBy: String?,
    val approvedAt: Long?,
    val deletedAt: Long?,
)

/** Map a generated `users` row into the server-side [AuthUser] projection. */
internal fun Users.toAuthUser(): AuthUser =
    AuthUser(
        id = id,
        email = email,
        emailNormalized = email_normalized,
        passwordHash = password_hash,
        role = UserRoleColumn.valueOf(role),
        displayName = display_name,
        status = UserStatusColumn.valueOf(status),
        createdAt = created_at,
        // can_edit / can_share are INTEGER 0/1 in SQLite; 0 ↔ false, anything else ↔ true,
        // matching the Exposed `bool` adapter that read non-zero as true.
        canEdit = can_edit != 0L,
        canShare = can_share != 0L,
        approvedBy = approved_by,
        approvedAt = approved_at,
        deletedAt = deleted_at,
    )

/** The wire-facing [User] contract for this user. Mirrors the old `UserEntity.toContract()`. */
internal fun AuthUser.toContract(): User =
    User(
        id = UserId(id),
        email = email,
        displayName = displayName,
        role = role.toContract(),
        status = status.toContract(),
        createdAt = createdAt,
        permissions = UserPermissions(canEdit = canEdit, canShare = canShare),
        approvedBy = approvedBy,
        approvedAt = approvedAt,
    )

/** Map a [UserStatus] contract value to its server-side enum column. */
internal fun UserStatus.toStatusColumn(): UserStatusColumn =
    when (this) {
        UserStatus.ACTIVE -> UserStatusColumn.ACTIVE
        UserStatus.PENDING_APPROVAL -> UserStatusColumn.PENDING_APPROVAL
        UserStatus.DENIED -> UserStatusColumn.DENIED
    }
