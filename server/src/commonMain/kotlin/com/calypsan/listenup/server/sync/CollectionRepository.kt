package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.api.SYSTEM_TYPE_ALL_BOOKS
import com.calypsan.listenup.server.api.SYSTEM_TYPE_INBOX
import com.calypsan.listenup.server.db.sqldelight.Collections
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import app.cash.sqldelight.db.SqlDriver
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for user-owned (and system-managed) collections — a
 * **global (cross-user)** aggregate.
 *
 * The base [SqlSyncableRepository] owns revision-bump / timestamp / created-vs-updated /
 * emit-after-commit orchestration; this class supplies only the collection-shaped pieces:
 *  - [substrate] — the [SyncableSubstrateQueries] adapter over `collectionsQueries`
 *  - [readPayload] / [readPayloads] — root-row reads by id (tombstone-inclusive)
 *  - [writePayload] — insert-or-update inside the open transaction
 *  - `CollectionSyncPayload.id` / `CollectionSyncPayload.revisionOf`
 *
 * **System collections are sync-invisible to members.** The server-only `collections.type`
 * column (`'NORMAL'` | `'ALL_BOOKS'` | `'INBOX'`) never crosses the wire — [CollectionSyncPayload.isInbox]
 * is projected from `type = 'INBOX'`. `writePayload` never writes `type`; the find-or-create
 * system-collection flow stamps it afterwards through [setType] (no revision bump, no publish).
 *
 * **Access-filtered sync.** A member's collection catch-up/digest must exclude the system
 * collections and any collection they cannot reach, so the firehose arrives with a non-null
 * [SqlFragment] `extraWhere` (the `accessibleCollectionIdsSql` rule). The base
 * [SqlSyncableRepository] splices it as `id IN (<extraWhere.sql>)` engine-neutrally over the
 * injected [SqlDriver]; this class only wires that driver. The unfiltered (admin / null) path
 * takes the base's substrate read unchanged.
 *
 * Service-layer helpers beyond the base substrate (each runs in its own transaction):
 *  - [findById] — one live collection by id, or null
 *  - [findInboxForLibrary] — the live inbox collection for a library
 *  - [findSystemCollection] — a per-library system collection by server-only `type`
 *  - [setType] — stamp the server-only `type` column (no revision bump / no publish)
 *  - [listOwnedBy] — all live collections owned by a user
 *  - [listAll] — all live collections (the admin god-view source)
 *  - [systemCollectionIds] — ids of every live system collection (the member-list exclusion set)
 */
class CollectionRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    override val driver: SqlDriver,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<CollectionSyncPayload, String>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.COLLECTIONS,
        clock = clock,
    ) {
    override val CollectionSyncPayload.id: String get() = this.id

    override fun CollectionSyncPayload.revisionOf(): Long = revision

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.collectionsQueries].
     *
     * The canonical global adapter shape: the four substrate methods forward to the matching
     * generated query, mapping revision-cursor rows into the engine-neutral [IdRev]. The
     * `*ForUser` variants are intentionally left as the base's throwing defaults — collections
     * are global and never route through them.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.collectionsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.collectionsQueries
                    .softDeleteById(
                        revision = revision,
                        updated_at = updatedAt,
                        deleted_at = deletedAt,
                        client_op_id = clientOpId,
                        id = id,
                    ).value

            override fun selectIdsAboveRevision(
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.collectionsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.collectionsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    // Tombstone-inclusive read by id — pullSince/readPayloads must hydrate soft-deleted rows so
    // clients receive tombstones.
    override fun readPayload(idStr: String): CollectionSyncPayload? =
        db.collectionsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<CollectionSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.collectionsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    override fun writePayload(
        value: CollectionSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            // `type` is deliberately untouched — it is server-only and stamped via setType.
            db.collectionsQueries.update(
                library_id = value.libraryId,
                owner_id = value.ownerId,
                name = value.name,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            // New rows land as 'NORMAL'; a system collection is stamped to its type via setType
            // immediately after the upsert materialises the row.
            db.collectionsQueries.insert(
                id = value.id,
                library_id = value.libraryId,
                owner_id = value.ownerId,
                name = value.name,
                type = "NORMAL",
                created_at = now,
                updated_at = now,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /** Returns the non-deleted collection with [id], or null when absent or tombstoned. */
    suspend fun findById(id: String): CollectionSyncPayload? =
        suspendTransaction(db) {
            db.collectionsQueries
                .selectLiveById(id)
                .executeAsOneOrNull()
                ?.toSyncPayload()
        }

    /**
     * Returns the live inbox collection for [libraryId], or null when none exists.
     *
     * At most one live inbox per library is enforced by a partial unique index; this query
     * returns the first match.
     */
    suspend fun findInboxForLibrary(libraryId: String): CollectionSyncPayload? =
        suspendTransaction(db) {
            db.collectionsQueries
                .selectLiveInboxForLibrary(libraryId)
                .executeAsOneOrNull()
                ?.toSyncPayload()
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
            db.collectionsQueries
                .selectLiveSystemForLibrary(libraryId, typeName)
                .executeAsOneOrNull()
                ?.toSyncPayload()
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
            db.collectionsQueries.setType(type = typeName, id = collectionId)
        }
    }

    /** Returns all non-deleted collections owned by [userId]. */
    suspend fun listOwnedBy(userId: String): List<CollectionSyncPayload> =
        suspendTransaction(db) {
            db.collectionsQueries
                .listLiveOwnedBy(userId)
                .executeAsList()
                .map { it.toSyncPayload() }
        }

    /** Returns all non-deleted collections across all users and libraries. */
    suspend fun listAll(): List<CollectionSyncPayload> =
        suspendTransaction(db) {
            db.collectionsQueries
                .listAllLive()
                .executeAsList()
                .map { it.toSyncPayload() }
        }

    /**
     * Returns the ids of all live system collections (ALL_BOOKS and INBOX).
     *
     * System collections are never member-facing: spec §3.2 states they must not appear in a
     * member's collection list. This query gives `CollectionServiceImpl.listCollections` the
     * exclusion set it needs for the non-admin path, without leaking the server-only `type`
     * column onto the wire or into the payload layer.
     */
    suspend fun systemCollectionIds(): Set<String> =
        suspendTransaction(db) {
            db.collectionsQueries
                .selectLiveSystemCollectionIds()
                .executeAsList()
                .toSet()
        }

    /** Maps a generated [Collections] row to the wire [CollectionSyncPayload] (`isInbox`/`isSystem` from `type`). */
    private fun Collections.toSyncPayload(): CollectionSyncPayload =
        CollectionSyncPayload(
            id = id,
            libraryId = library_id,
            ownerId = owner_id,
            name = name,
            isInbox = type == SYSTEM_TYPE_INBOX,
            isSystem = type == SYSTEM_TYPE_INBOX || type == SYSTEM_TYPE_ALL_BOOKS,
            revision = revision,
            updatedAt = updated_at,
            deletedAt = deleted_at,
        )

    private companion object {
        /**
         * Chunk size for `IN (…)` batch reads. Kept under SQLite's default
         * `SQLITE_MAX_VARIABLE_NUMBER` (999) with headroom for any fixed bind params.
         */
        const val SQLITE_IN_CHUNK = 900
    }
}
