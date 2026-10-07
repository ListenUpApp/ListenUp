package com.calypsan.listenup.server.sync

import app.cash.sqldelight.TransactionWithReturn
import com.calypsan.listenup.api.sync.ReadingOrderSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.domain.readingorder.ReadingOrderName
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Reading_orders
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for reading orders (#962) — a GLOBAL, ungated domain like series:
 * every authenticated user receives every row. The owner column is `created_by`, not `user_id`, because
 * orders are library-wide rather than user-scoped.
 *
 * Each write stores the [ReadingOrderName.normalize]d name beside the display name, so the per-series
 * uniqueness index and [liveIdForName] agree with the client's own "already exists" check. The maker
 * ([ReadingOrderSyncPayload.createdBy]) is written on insert and never rewritten.
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
    override fun idAsString(id: ReadingOrderId): String = id.value

    override val ReadingOrderSyncPayload.id: ReadingOrderId get() = ReadingOrderId(this.id)

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
        }

    // Tombstone-inclusive read by id — pullSince/readPayloads must hydrate soft-deleted rows.
    override fun readPayload(idStr: String): ReadingOrderSyncPayload? =
        db.readingOrdersQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<ReadingOrderSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
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
                series_id = value.seriesId,
                name = value.name,
                normalized_name = ReadingOrderName.normalize(value.name),
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.readingOrdersQueries.insert(
                id = value.id,
                series_id = value.seriesId,
                name = value.name,
                normalized_name = ReadingOrderName.normalize(value.name),
                created_by = value.createdBy,
                created_at = now,
                updated_at = now,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /** The live order with [id], or null when absent or tombstoned. */
    suspend fun findLive(id: String): ReadingOrderSyncPayload? =
        suspendTransaction(db) { readPayload(id)?.takeIf { it.deletedAt == null } }

    /** The order with [id] in any state (tombstones included), or null — the idempotent-create check. */
    suspend fun findAny(id: String): ReadingOrderSyncPayload? = suspendTransaction(db) { readPayload(id) }

    /** The id of the live order on [seriesId] whose name normalizes like [name], or null. */
    suspend fun liveIdForName(
        seriesId: String,
        name: String,
    ): String? =
        suspendTransaction(db) {
            db.readingOrdersQueries
                .selectLiveIdByName(series_id = seriesId, normalized_name = ReadingOrderName.normalize(name))
                .executeAsOneOrNull()
        }

    /** Tombstones order [id] inside the caller's open transaction; false when no such order exists. */
    internal fun TransactionWithReturn<*>.tombstoneOrder(
        id: ReadingOrderId,
        suppressed: Boolean,
    ): Boolean = softDeleteInOpenTransaction(id, suppressed) != null

    private fun Reading_orders.toSyncPayload(): ReadingOrderSyncPayload =
        ReadingOrderSyncPayload(
            id = id,
            seriesId = series_id,
            name = name,
            createdBy = created_by,
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
        )

    private companion object {
        /** Chunk size for `IN (…)` reads, under SQLite's default 999-variable cap. */
        const val SQLITE_IN_CHUNK = 900
    }
}
