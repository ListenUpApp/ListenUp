package com.calypsan.listenup.server.db

import com.calypsan.listenup.api.dto.invite.InviteDto
import com.calypsan.listenup.api.dto.invite.InviteId
import com.calypsan.listenup.server.auth.toContract
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass

/**
 * Persistence schema for the `invites` table created by V27__invites.sql.
 * The `role` enum is stored by name; the width matches [UserTable]'s role column
 * so an invited role round-trips identically to a persisted user role.
 */
object InviteTable : IdTable<String>("invites") {
    // Headroom for future role names (current max: MEMBER, 6 chars). Mirrors UserTable.
    private const val ROLE_COLUMN_LENGTH = 16

    override val id: Column<EntityID<String>> = text("id").entityId()
    val code = text("code").uniqueIndex()
    val email = text("email")
    val displayName = text("display_name")
    val role = enumerationByName("role", ROLE_COLUMN_LENGTH, UserRoleColumn::class)
    val createdBy = text("created_by")
    val expiresAt = long("expires_at")
    val claimedAt = long("claimed_at").nullable()
    val claimedBy = text("claimed_by").nullable()
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

class InviteEntity(
    id: EntityID<String>,
) : Entity<String>(id) {
    companion object : EntityClass<String, InviteEntity>(InviteTable)

    var code by InviteTable.code
    var email by InviteTable.email
    var displayName by InviteTable.displayName
    var role by InviteTable.role
    var createdBy by InviteTable.createdBy
    var expiresAt by InviteTable.expiresAt
    var claimedAt by InviteTable.claimedAt
    var claimedBy by InviteTable.claimedBy
    var createdAt by InviteTable.createdAt
}

internal fun InviteEntity.toDto(): InviteDto =
    InviteDto(
        id = InviteId(id.value),
        code = code,
        email = email,
        displayName = displayName,
        role = role.toContract(),
        createdBy = createdBy,
        expiresAt = expiresAt,
        createdAt = createdAt,
        claimedAt = claimedAt,
        claimedBy = claimedBy,
    )
