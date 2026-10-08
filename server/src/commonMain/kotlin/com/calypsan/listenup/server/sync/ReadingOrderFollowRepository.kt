package com.calypsan.listenup.server.sync

import app.cash.sqldelight.TransactionWithReturn
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoiceKind
import com.calypsan.listenup.api.sync.ReadingOrderFollowSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Reading_order_follows
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/** A live follow of an order, with the user who owns it — the delete cascade's fan-out unit. */
data class ReadingOrderFollower(
    val followId: String,
    val userId: String,
)

/**
 * SQLDelight syncable repository for each user's choice of reading order per series (#962) —
 * user-scoped like shelves: the owning `user_id` is stamped on insert and every pull/digest filters to
 * the caller, so one user's choices never reach another. The row id is the deterministic
 * [followId], computable on both sides, so a choice made offline and one made online land on the same row.
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

    override fun readPayload(idStr: String): ReadingOrderFollowSyncPayload? =
        db.readingOrderFollowsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<ReadingOrderFollowSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
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
                choice = value.choice.name,
                reading_order_id = value.readingOrderId,
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
                choice = value.choice.name,
                reading_order_id = value.readingOrderId,
                created_at = now,
                updated_at = now,
                revision = rev,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /** The live follow with [id], or null when there is none or it is tombstoned (the series inherits). */
    suspend fun findLive(id: String): ReadingOrderFollowSyncPayload? =
        suspendTransaction(db) { readPayload(id)?.takeIf { it.deletedAt == null } }

    /**
     * Tombstones every user's live follow of [orderId] inside the caller's open transaction — part of the
     * order's delete. Each tombstone is published to its owner only, so each follower's series falls back
     * to inheritance on every device.
     */
    internal fun TransactionWithReturn<*>.tombstoneFollowsOf(
        orderId: String,
        suppressed: Boolean,
    ): Int {
        val followers =
            db.readingOrderFollowsQueries
                .selectLiveFollowersOfOrder(orderId) { id, userId -> ReadingOrderFollower(id, userId) }
                .executeAsList()
        for (follower in followers) {
            softDeleteInOpenTransaction(follower.followId, suppressed, userId = follower.userId)
        }
        return followers.size
    }

    /** "<userId>:<seriesId>" — the deterministic follow id both sides compute. */
    fun followId(
        userId: String,
        seriesId: String,
    ): String = "$userId:$seriesId"

    /** Every live follow of [orderId], across users. */
    suspend fun liveFollowersOf(orderId: String): List<ReadingOrderFollower> =
        suspendTransaction(db) {
            db.readingOrderFollowsQueries
                .selectLiveFollowersOfOrder(orderId) { id, userId -> ReadingOrderFollower(id, userId) }
                .executeAsList()
        }

    /** How many users currently follow [orderId]. */
    suspend fun countFollowersOf(orderId: String): Int =
        suspendTransaction(db) {
            db.readingOrderFollowsQueries
                .countLiveFollowersOfOrder(orderId)
                .executeAsOne()
                .toInt()
        }

    private fun Reading_order_follows.toSyncPayload(): ReadingOrderFollowSyncPayload =
        ReadingOrderFollowSyncPayload(
            id = id,
            seriesId = series_id,
            // A stored kind this binary does not know can only come from a newer one; read it as the
            // default rather than failing the whole read.
            choice = ReadingOrderChoiceKind.entries.firstOrNull { it.name == choice } ?: ReadingOrderChoiceKind.SERIES,
            readingOrderId = reading_order_id,
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
