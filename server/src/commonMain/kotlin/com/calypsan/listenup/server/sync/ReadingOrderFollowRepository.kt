package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.ReadingOrderFollowSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Reading_order_follows
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for per-user, per-series reading-order
 * follow-state (Integration Foundations §5.4).
 *
 * `userScoped = true` — every write stamps the owning `user_id` and pull/digest
 * filter to the authenticated user via the substrate's `*ForUser` variants, so a
 * follow is strictly personal.
 *
 * Row identity is the deterministic synthetic id `"$userId:$seriesId"` — unlike
 * the playback-position UUID, both sides can compute it before any echo, so the
 * client's optimistic row, the server row, and the digest agree on identity and
 * the domain participates fully in digest reconciliation.
 *
 * Service-layer helper beyond the base substrate:
 *  - [findLive] — the live follow row for a `(user, series)` pair, or null.
 */
class ReadingOrderFollowRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<ReadingOrderFollowSyncPayload, String>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.READING_ORDER_FOLLOWS,
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override val ReadingOrderFollowSyncPayload.id: String get() = this.id

    override fun ReadingOrderFollowSyncPayload.revisionOf(): Long = revision

    /**
     * [SyncableSubstrateQueries] adapter over the generated
     * [ListenUpDatabase.readingOrderFollowsQueries] — the canonical user-scoped
     * adapter shape.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.readingOrderFollowsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.readingOrderFollowsQueries
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
                db.readingOrderFollowsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.readingOrderFollowsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdsAboveRevisionForUser(
                userId: String,
                cursor: Long,
                limit: Long,
            ): List<IdRev> =
                db.readingOrderFollowsQueries
                    .selectIdsAboveRevisionForUser(userId, cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMostForUser(
                userId: String,
                cursor: Long,
            ): List<IdRev> =
                db.readingOrderFollowsQueries
                    .selectIdRevAtMostForUser(userId, cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    // Tombstone-inclusive read by id — pullSince/readPayloads must hydrate soft-deleted
    // rows so clients receive tombstones.
    override fun readPayload(idStr: String): ReadingOrderFollowSyncPayload? =
        db.readingOrderFollowsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<ReadingOrderFollowSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.readingOrderFollowsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    override fun writePayload(
        value: ReadingOrderFollowSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.readingOrderFollowsQueries.update(
                active_reading_order_id = value.activeReadingOrderId,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.readingOrderFollowsQueries.insert(
                id = value.id,
                user_id = requireNotNull(userId) { "ReadingOrderFollowRepository.writePayload requires a userId" },
                series_id = value.seriesId,
                active_reading_order_id = value.activeReadingOrderId,
                created_at = now,
                updated_at = now,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /** Returns the live follow row for `(userId, seriesId)`, or null when absent. */
    suspend fun findLive(
        userId: String,
        seriesId: String,
    ): ReadingOrderFollowSyncPayload? =
        suspendTransaction(db) {
            db.readingOrderFollowsQueries
                .selectLiveByUserAndSeries(userId, seriesId)
                .executeAsOneOrNull()
                ?.toSyncPayload()
        }

    /** Maps a generated [Reading_order_follows] row to the wire DTO (drops `user_id`). */
    private fun Reading_order_follows.toSyncPayload(): ReadingOrderFollowSyncPayload =
        ReadingOrderFollowSyncPayload(
            id = id,
            seriesId = series_id,
            activeReadingOrderId = active_reading_order_id,
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
    }
}
