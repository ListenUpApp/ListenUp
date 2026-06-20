package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.db.CollectionsTable
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
 * Syncable repository for user-owned collections.
 *
 * Handles the full collection aggregate: read/write of [CollectionSyncPayload]
 * via [CollectionsTable]. Collections are a global sync domain (`userScoped = false`);
 * per-user visibility is enforced at the service layer, not here.
 *
 * Service-layer helpers beyond the base substrate:
 *  - [findById] — fetch one non-deleted collection by id
 *  - [findInboxForLibrary] — fetch the inbox collection for a library
 *  - [findSystemCollection] — fetch a per-library system collection by server-only `type`
 *  - [setType] — stamp the server-only `type` column (no revision bump / no publish)
 *  - [listOwnedBy] — fetch all non-deleted collections owned by a user
 *  - [listAll] — fetch all non-deleted collections
 */
class CollectionRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<CollectionSyncPayload, String>(
        db = db,
        table = CollectionsTable,
        bus = bus,
        registry = registry,
        domainName = "collections",
        clock = clock,
    ) {
    override val elementSerializer: KSerializer<CollectionSyncPayload> = CollectionSyncPayload.serializer()

    override val CollectionSyncPayload.id: String get() = this.id

    override fun CollectionSyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): CollectionSyncPayload? =
        CollectionsTable
            .selectAll()
            .where { CollectionsTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                CollectionSyncPayload(
                    id = row[CollectionsTable.id],
                    libraryId = row[CollectionsTable.libraryId],
                    ownerId = row[CollectionsTable.ownerId],
                    name = row[CollectionsTable.name],
                    isInbox = row[CollectionsTable.isInbox],
                    revision = row[CollectionsTable.revision],
                    updatedAt = row[CollectionsTable.updatedAt],
                    deletedAt = row[CollectionsTable.deletedAt],
                )
            }

    override suspend fun writePayload(
        value: CollectionSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            CollectionsTable.update({ CollectionsTable.id eq value.id }) { stmt ->
                stmt[CollectionsTable.libraryId] = value.libraryId
                stmt[CollectionsTable.ownerId] = value.ownerId
                stmt[CollectionsTable.name] = value.name
                stmt[CollectionsTable.isInbox] = value.isInbox
                stmt[CollectionsTable.revision] = rev
                stmt[CollectionsTable.updatedAt] = now
                stmt[CollectionsTable.deletedAt] = null
                stmt[CollectionsTable.clientOpId] = clientOpId
            }
        } else {
            CollectionsTable.insert { stmt ->
                stmt[CollectionsTable.id] = value.id
                stmt[CollectionsTable.libraryId] = value.libraryId
                stmt[CollectionsTable.ownerId] = value.ownerId
                stmt[CollectionsTable.name] = value.name
                stmt[CollectionsTable.isInbox] = value.isInbox
                stmt[CollectionsTable.revision] = rev
                stmt[CollectionsTable.createdAt] = now
                stmt[CollectionsTable.updatedAt] = now
                stmt[CollectionsTable.deletedAt] = null
                stmt[CollectionsTable.clientOpId] = clientOpId
            }
        }
    }

    /**
     * Returns the non-deleted collection with [id], or null when absent or tombstoned.
     */
    suspend fun findById(id: String): CollectionSyncPayload? =
        suspendTransaction(db) {
            CollectionsTable
                .selectAll()
                .where { (CollectionsTable.id eq id) and CollectionsTable.deletedAt.isNull() }
                .firstOrNull()
                ?.let { row ->
                    CollectionSyncPayload(
                        id = row[CollectionsTable.id],
                        libraryId = row[CollectionsTable.libraryId],
                        ownerId = row[CollectionsTable.ownerId],
                        name = row[CollectionsTable.name],
                        isInbox = row[CollectionsTable.isInbox],
                        revision = row[CollectionsTable.revision],
                        updatedAt = row[CollectionsTable.updatedAt],
                        deletedAt = row[CollectionsTable.deletedAt],
                    )
                }
        }

    /**
     * Returns the live inbox collection for [libraryId], or null when none exists.
     *
     * At most one live inbox per library is enforced by a partial unique index in the
     * migration; this query simply returns the first match.
     */
    suspend fun findInboxForLibrary(libraryId: String): CollectionSyncPayload? =
        suspendTransaction(db) {
            CollectionsTable
                .selectAll()
                .where {
                    (CollectionsTable.libraryId eq libraryId) and
                        (CollectionsTable.isInbox eq true) and
                        CollectionsTable.deletedAt.isNull()
                }.firstOrNull()
                ?.let { row ->
                    CollectionSyncPayload(
                        id = row[CollectionsTable.id],
                        libraryId = row[CollectionsTable.libraryId],
                        ownerId = row[CollectionsTable.ownerId],
                        name = row[CollectionsTable.name],
                        isInbox = row[CollectionsTable.isInbox],
                        revision = row[CollectionsTable.revision],
                        updatedAt = row[CollectionsTable.updatedAt],
                        deletedAt = row[CollectionsTable.deletedAt],
                    )
                }
        }

    /**
     * Returns the live system collection of [typeName] for [libraryId], or null when none exists.
     *
     * The `type` column is server-only (never on the wire) and identifies a per-library system
     * collection — `"ALL_BOOKS"` or `"INBOX"`. At most one live row per `(libraryId, type)` is
     * the find-or-create invariant; this query returns the first match.
     */
    suspend fun findSystemCollection(
        libraryId: String,
        typeName: String,
    ): CollectionSyncPayload? =
        suspendTransaction(db) {
            CollectionsTable
                .selectAll()
                .where {
                    (CollectionsTable.libraryId eq libraryId) and
                        (CollectionsTable.type eq typeName) and
                        CollectionsTable.deletedAt.isNull()
                }.firstOrNull()
                ?.let { row ->
                    CollectionSyncPayload(
                        id = row[CollectionsTable.id],
                        libraryId = row[CollectionsTable.libraryId],
                        ownerId = row[CollectionsTable.ownerId],
                        name = row[CollectionsTable.name],
                        isInbox = row[CollectionsTable.isInbox],
                        revision = row[CollectionsTable.revision],
                        updatedAt = row[CollectionsTable.updatedAt],
                        deletedAt = row[CollectionsTable.deletedAt],
                    )
                }
        }

    /**
     * Sets the server-only `type` column for [collectionId] to [typeName].
     *
     * The `type` column never crosses the wire, so this is a bare column write — no revision
     * bump and no sync publish. Used by the find-or-create system-collection flow to stamp the
     * type after the row is materialised through the normal [upsert] path.
     */
    suspend fun setType(
        collectionId: String,
        typeName: String,
    ) {
        suspendTransaction(db) {
            CollectionsTable.update({ CollectionsTable.id eq collectionId }) { stmt ->
                stmt[CollectionsTable.type] = typeName
            }
        }
    }

    /**
     * Returns all non-deleted collections owned by [userId].
     */
    suspend fun listOwnedBy(userId: String): List<CollectionSyncPayload> =
        suspendTransaction(db) {
            CollectionsTable
                .selectAll()
                .where { (CollectionsTable.ownerId eq userId) and CollectionsTable.deletedAt.isNull() }
                .map { row ->
                    CollectionSyncPayload(
                        id = row[CollectionsTable.id],
                        libraryId = row[CollectionsTable.libraryId],
                        ownerId = row[CollectionsTable.ownerId],
                        name = row[CollectionsTable.name],
                        isInbox = row[CollectionsTable.isInbox],
                        revision = row[CollectionsTable.revision],
                        updatedAt = row[CollectionsTable.updatedAt],
                        deletedAt = row[CollectionsTable.deletedAt],
                    )
                }
        }

    /**
     * Returns all non-deleted collections across all users and libraries.
     */
    suspend fun listAll(): List<CollectionSyncPayload> =
        suspendTransaction(db) {
            CollectionsTable
                .selectAll()
                .where { CollectionsTable.deletedAt.isNull() }
                .map { row ->
                    CollectionSyncPayload(
                        id = row[CollectionsTable.id],
                        libraryId = row[CollectionsTable.libraryId],
                        ownerId = row[CollectionsTable.ownerId],
                        name = row[CollectionsTable.name],
                        isInbox = row[CollectionsTable.isInbox],
                        revision = row[CollectionsTable.revision],
                        updatedAt = row[CollectionsTable.updatedAt],
                        deletedAt = row[CollectionsTable.deletedAt],
                    )
                }
        }
}
