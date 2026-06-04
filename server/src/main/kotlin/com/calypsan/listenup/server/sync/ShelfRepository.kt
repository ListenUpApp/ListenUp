package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.ShelfSyncPayload
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.server.db.ShelvesTable
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Syncable repository for per-user, user-owned shelves.
 *
 * `userScoped = true` — every `upsert`, `softDelete`, `pullSince`, and `digest`
 * routes through the per-user dimension of the substrate: the owning `user_id`
 * is stamped on insert and every read/digest filters by the authenticated user.
 *
 * `idAsString(ShelfId) = id.value` is load-bearing — Kotlin's default `toString()`
 * on a value class returns `"ShelfId(value=foo)"`, which would corrupt every column
 * the id is written to.
 *
 * Service-layer helpers beyond the base substrate:
 *  - [listOwnedBy] — all live shelves for a user
 *  - [findById] — one live shelf by id, or null
 */
class ShelfRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<ShelfSyncPayload, ShelfId>(
        db = db,
        table = ShelvesTable,
        bus = bus,
        registry = registry,
        domainName = "shelves",
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override val elementSerializer: KSerializer<ShelfSyncPayload> = ShelfSyncPayload.serializer()

    override fun idAsString(id: ShelfId): String = id.value

    override val ShelfSyncPayload.id: ShelfId get() = ShelfId(this.id)

    override fun ShelfSyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): ShelfSyncPayload? =
        ShelvesTable
            .selectAll()
            .where { ShelvesTable.id eq idStr }
            .firstOrNull()
            ?.toSyncPayload()

    override suspend fun writePayload(
        value: ShelfSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        requireNotNull(userId) { "ShelfRepository.writePayload requires a userId" }
        if (existed) {
            ShelvesTable.update({ ShelvesTable.id eq value.id }) { stmt ->
                stmt[ShelvesTable.name] = value.name
                stmt[ShelvesTable.description] = value.description
                stmt[ShelvesTable.isPrivate] = value.isPrivate
                stmt[ShelvesTable.revision] = rev
                stmt[ShelvesTable.updatedAt] = now
                stmt[ShelvesTable.deletedAt] = null
                stmt[ShelvesTable.clientOpId] = clientOpId
            }
        } else {
            ShelvesTable.insert { stmt ->
                stmt[ShelvesTable.id] = value.id
                stmt[ShelvesTable.userId] = userId
                stmt[ShelvesTable.name] = value.name
                stmt[ShelvesTable.description] = value.description
                stmt[ShelvesTable.isPrivate] = value.isPrivate
                stmt[ShelvesTable.revision] = rev
                stmt[ShelvesTable.createdAt] = now
                stmt[ShelvesTable.updatedAt] = now
                stmt[ShelvesTable.deletedAt] = null
                stmt[ShelvesTable.clientOpId] = clientOpId
            }
        }
    }

    /** Returns all live (non-tombstoned) shelves owned by [userId]. */
    suspend fun listOwnedBy(userId: String): List<ShelfSyncPayload> =
        suspendTransaction(db) {
            ShelvesTable
                .selectAll()
                .where { (ShelvesTable.userId eq userId) and ShelvesTable.deletedAt.isNull() }
                .map { row -> row.toSyncPayload() }
        }

    /** Returns the live shelf with [id], or null when absent or tombstoned. */
    suspend fun findById(id: String): ShelfSyncPayload? =
        suspendTransaction(db) {
            ShelvesTable
                .selectAll()
                .where { (ShelvesTable.id eq id) and ShelvesTable.deletedAt.isNull() }
                .firstOrNull()
                ?.toSyncPayload()
        }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toSyncPayload(): ShelfSyncPayload =
        ShelfSyncPayload(
            id = this[ShelvesTable.id],
            name = this[ShelvesTable.name],
            description = this[ShelvesTable.description],
            isPrivate = this[ShelvesTable.isPrivate],
            revision = this[ShelvesTable.revision],
            updatedAt = this[ShelvesTable.updatedAt],
            createdAt = this[ShelvesTable.createdAt],
            deletedAt = this[ShelvesTable.deletedAt],
        )
}
