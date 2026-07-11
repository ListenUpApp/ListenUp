package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ReadingOrderSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Reading_orders
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * A live reading order paired with its owner's user id.
 *
 * The wire [ReadingOrderSyncPayload] omits `user_id` (it never crosses to other
 * users), so service-layer ownership gating and discovery read the owner through
 * this projection.
 *
 * @property ownerId The user id that owns [readingOrder].
 * @property readingOrder The reading-order payload (without owner identity).
 */
data class OwnedReadingOrder(
    val ownerId: String,
    val readingOrder: ReadingOrderSyncPayload,
)

/**
 * SQLDelight syncable repository for per-user, user-owned reading orders — a
 * near-exact sibling of [ShelfRepository] (spec §5.3, the Reading Orders fold-in).
 *
 * `userScoped = true` — every `upsert`, `softDelete`, `pullSince`, and `digest`
 * routes through the per-user dimension of the [SqlSyncableRepository] base: the
 * owning `user_id` is stamped on insert, and pull/digest filter to the authenticated
 * user via the substrate's `*ForUser` variants.
 *
 * `idAsString(ReadingOrderId) = id.value` is load-bearing — Kotlin's default
 * `toString()` on a value class returns `"ReadingOrderId(value=foo)"`, which would
 * corrupt every column the id is written to.
 *
 * Service-layer helpers beyond the base substrate (each runs in its own transaction):
 *  - [listOwnedBy] — all live reading orders for a user
 *  - [findById] — one live reading order by id, or null
 *  - [findOwnedById] — one live reading order + its owner id, or null
 *  - [listForOwner] — every live public reading order owned by a user (with owner id)
 *  - [listDiscoverable] — every live public reading order NOT owned by a user (with owner id)
 */
class ReadingOrderRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<ReadingOrderSyncPayload, ReadingOrderId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.READING_ORDERS,
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override fun idAsString(id: ReadingOrderId): String = id.value

    override val ReadingOrderSyncPayload.id: ReadingOrderId get() = ReadingOrderId(this.id)

    override fun ReadingOrderSyncPayload.revisionOf(): Long = revision

    /**
     * [SyncableSubstrateQueries] adapter over the generated
     * [ListenUpDatabase.readingOrdersQueries]. The canonical user-scoped adapter
     * shape: the four global substrate methods forward to the unfiltered queries;
     * the two `*ForUser` methods forward to the user-filtered queries the base
     * calls when [userScoped] is true.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.readingOrdersQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.readingOrdersQueries
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
                db.readingOrdersQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.readingOrdersQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdsAboveRevisionForUser(
                userId: String,
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.readingOrdersQueries
                    .selectIdsAboveRevisionForUser(userId, cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMostForUser(
                userId: String,
                cursor: Long,
            ): List<IdRev> =
                db.readingOrdersQueries
                    .selectIdRevAtMostForUser(userId, cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    // Tombstone-inclusive read by id — pullSince/readPayloads must hydrate soft-deleted
    // rows so clients receive tombstones.
    override fun readPayload(idStr: String): ReadingOrderSyncPayload? =
        db.readingOrdersQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<ReadingOrderSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.readingOrdersQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    override fun writePayload(
        value: ReadingOrderSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.readingOrdersQueries.update(
                name = value.name,
                description = value.description,
                attribution = value.attribution,
                is_private = value.isPrivate.toDbLong(),
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.readingOrdersQueries.insert(
                id = value.id,
                user_id = requireNotNull(userId) { "ReadingOrderRepository.writePayload requires a userId" },
                name = value.name,
                description = value.description,
                attribution = value.attribution,
                is_private = value.isPrivate.toDbLong(),
                created_at = now,
                updated_at = now,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /** Returns all live (non-tombstoned) reading orders owned by [userId]. */
    suspend fun listOwnedBy(userId: String): List<ReadingOrderSyncPayload> =
        suspendTransaction(db) {
            db.readingOrdersQueries
                .listOwnedBy(userId)
                .executeAsList()
                .map { it.toSyncPayload() }
        }

    /** Returns the live reading order with [id], or null when absent or tombstoned. */
    suspend fun findById(id: String): ReadingOrderSyncPayload? =
        suspendTransaction(db) {
            db.readingOrdersQueries
                .selectLiveById(id)
                .executeAsOneOrNull()
                ?.toSyncPayload()
        }

    /**
     * Returns the live reading order with [id] alongside its owner's user id, or
     * null when absent or tombstoned. The wire [ReadingOrderSyncPayload]
     * deliberately omits `user_id`, so service-layer ownership gating reads the
     * owner through this projection rather than the payload.
     */
    suspend fun findOwnedById(id: String): OwnedReadingOrder? =
        suspendTransaction(db) {
            db.readingOrdersQueries
                .selectLiveById(id)
                .executeAsOneOrNull()
                ?.toOwnedReadingOrder()
        }

    /**
     * Returns every live, public reading order owned by [ownerId], each paired with
     * its owner's user id — the building block for
     * [getUserReadingOrders][com.calypsan.listenup.server.api.ReadingOrderServiceImpl.getUserReadingOrders].
     * A direct service-layer read that crosses the user-scoping boundary by design.
     */
    suspend fun listForOwner(ownerId: String): List<OwnedReadingOrder> =
        suspendTransaction(db) {
            db.readingOrdersQueries
                .listPublicForOwner(ownerId)
                .executeAsList()
                .map { it.toOwnedReadingOrder() }
        }

    /**
     * Returns every live, public reading order NOT owned by [excludeUserId], each
     * paired with its owner's user id — the discovery candidate set before
     * per-caller book-access filtering. A direct service-layer read (not a
     * sync-substrate pull): discovery crosses the user-scoping boundary by design.
     */
    suspend fun listDiscoverable(excludeUserId: String): List<OwnedReadingOrder> =
        suspendTransaction(db) {
            db.readingOrdersQueries
                .listDiscoverable(excludeUserId)
                .executeAsList()
                .map { it.toOwnedReadingOrder() }
        }

    /** Maps a generated [Reading_orders] row to an [OwnedReadingOrder] (owner id + wire payload). */
    private fun Reading_orders.toOwnedReadingOrder(): OwnedReadingOrder =
        OwnedReadingOrder(ownerId = user_id, readingOrder = toSyncPayload())

    /** Maps a generated [Reading_orders] row to the wire [ReadingOrderSyncPayload] DTO (drops `user_id`). */
    private fun Reading_orders.toSyncPayload(): ReadingOrderSyncPayload =
        ReadingOrderSyncPayload(
            id = id,
            name = name,
            description = description,
            attribution = attribution,
            // `is_private` is INTEGER (0/1) in SQLite — SQLDelight maps it to Long; convert at
            // the boundary, the same place the Exposed bool adapter sat.
            isPrivate = is_private == 1L,
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
        )

    private companion object {
        /**
         * Chunk size for `IN (…)` batch reads. Kept under SQLite's default
         * `SQLITE_MAX_VARIABLE_NUMBER` (999) with headroom for any fixed bind params.
         */
        const val SQLITE_IN_CHUNK = 900

        /** SQLite stores booleans as INTEGER 0/1; map at the write boundary. */
        private fun Boolean.toDbLong(): Long = if (this) 1L else 0L
    }
}
