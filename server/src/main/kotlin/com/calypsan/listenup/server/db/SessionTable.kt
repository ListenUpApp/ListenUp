package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass

/**
 * Persistence schema for the `sessions` table created by V1__auth_baseline.sql.
 * The `user_id` foreign key cascades on delete — deleting a user purges their
 * sessions (matches V1's `ON DELETE CASCADE`; relies on `PRAGMA foreign_keys = ON`
 * set by `DatabaseFactory` for SQLite).
 */
object SessionTable : IdTable<String>("sessions") {
    override val id: Column<EntityID<String>> = text("id").entityId()
    val userId = reference("user_id", UserTable, onDelete = ReferenceOption.CASCADE)
    val refreshTokenHash = text("refresh_token_hash")
    val familyId = text("family_id")
    val previousHash = text("previous_hash").nullable()
    val label = text("label").nullable()
    val deviceType = text("device_type").nullable()
    val platform = text("platform").nullable()
    val platformVersion = text("platform_version").nullable()
    val clientName = text("client_name").nullable()
    val clientVersion = text("client_version").nullable()
    val deviceName = text("device_name").nullable()
    val deviceModel = text("device_model").nullable()
    val userAgent = text("user_agent").nullable()
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val lastUsedAt = long("last_used_at")
    val revokedAt = long("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

class SessionEntity(
    id: EntityID<String>,
) : Entity<String>(id) {
    companion object : EntityClass<String, SessionEntity>(SessionTable)

    var user by UserEntity referencedOn SessionTable.userId
    var refreshTokenHash by SessionTable.refreshTokenHash
    var familyId by SessionTable.familyId
    var previousHash by SessionTable.previousHash
    var label by SessionTable.label
    var deviceType by SessionTable.deviceType
    var platform by SessionTable.platform
    var platformVersion by SessionTable.platformVersion
    var clientName by SessionTable.clientName
    var clientVersion by SessionTable.clientVersion
    var deviceName by SessionTable.deviceName
    var deviceModel by SessionTable.deviceModel
    var userAgent by SessionTable.userAgent
    var createdAt by SessionTable.createdAt
    var expiresAt by SessionTable.expiresAt
    var lastUsedAt by SessionTable.lastUsedAt
    var revokedAt by SessionTable.revokedAt
}
