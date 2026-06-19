package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass

/** Server-side enum for `users.role`. Maps 1:1 with commonMain `UserRole`. */
enum class UserRoleColumn { ROOT, ADMIN, MEMBER }

/** Server-side enum for `users.status`. Maps 1:1 with commonMain `UserStatus`. */
enum class UserStatusColumn { ACTIVE, PENDING_APPROVAL, DENIED }

/**
 * Persistence schema for the `users` table created by V1__auth_baseline.sql.
 * Enum columns are stored by name; widths give headroom for future variants
 * without an additional migration.
 */
object UserTable : IdTable<String>("users") {
    // Headroom for future role names (current max: MEMBER, 6 chars).
    private const val ROLE_COLUMN_LENGTH = 16

    // Headroom for future status names (current max: PENDING_APPROVAL, 16 chars).
    private const val STATUS_COLUMN_LENGTH = 24

    override val id: Column<EntityID<String>> = text("id").entityId()
    val email = text("email")
    val emailNormalized = text("email_normalized").uniqueIndex()
    val passwordHash = text("password_hash")
    val role = enumerationByName("role", ROLE_COLUMN_LENGTH, UserRoleColumn::class)
    val displayName = text("display_name")
    val tagline = text("tagline").nullable()
    val avatarType = text("avatar_type").default("auto")
    val timezone = text("timezone").default("UTC")
    val status = enumerationByName("status", STATUS_COLUMN_LENGTH, UserStatusColumn::class)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val lastLoginAt = long("last_login_at").nullable()
    val canEdit = bool("can_edit").default(true)
    val canShare = bool("can_share").default(true)
    val approvedBy = text("approved_by").nullable()
    val approvedAt = long("approved_at").nullable()
    val invitedBy = text("invited_by").nullable()
    val deletedAt = long("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

class UserEntity(
    id: EntityID<String>,
) : Entity<String>(id) {
    companion object : EntityClass<String, UserEntity>(UserTable)

    var email by UserTable.email
    var emailNormalized by UserTable.emailNormalized
    var passwordHash by UserTable.passwordHash
    var role by UserTable.role
    var displayName by UserTable.displayName
    var tagline by UserTable.tagline
    var avatarType by UserTable.avatarType
    var timezone by UserTable.timezone
    var status by UserTable.status
    var createdAt by UserTable.createdAt
    var updatedAt by UserTable.updatedAt
    var lastLoginAt by UserTable.lastLoginAt
    var canEdit by UserTable.canEdit
    var canShare by UserTable.canShare
    var approvedBy by UserTable.approvedBy
    var approvedAt by UserTable.approvedAt
    var invitedBy by UserTable.invitedBy
    var deletedAt by UserTable.deletedAt
}
