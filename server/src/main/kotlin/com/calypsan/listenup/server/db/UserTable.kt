package com.calypsan.listenup.server.db

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column

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
    val status = enumerationByName("status", STATUS_COLUMN_LENGTH, UserStatusColumn::class)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val lastLoginAt = long("last_login_at").nullable()

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
    var status by UserTable.status
    var createdAt by UserTable.createdAt
    var updatedAt by UserTable.updatedAt
    var lastLoginAt by UserTable.lastLoginAt
}
