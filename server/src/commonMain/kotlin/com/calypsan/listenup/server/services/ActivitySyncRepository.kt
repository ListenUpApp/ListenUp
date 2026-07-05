package com.calypsan.listenup.server.services

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.sync.ActivitySyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.db.sqldelight.Activities
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for the social activity feed (Phase 2 — sync-core).
 *
 * One row per recordable user action (started/finished a book, streak/listening milestone, shelf
 * created, listening session). **Server-authored, append-only, book-gated but GLOBAL** (not
 * per-user): a row with a non-null `book_id` is visible only to callers who can access that book;
 * `book_id == null` rows are public. Because access is by book — not by owning user — this clones
 * the `books` access-filtered mirror shape (`userScoped = false`, an injected [driver] so the
 * base's access-filtered `pullSince`/`digest` path splices the visibility subquery), NOT the
 * per-user `listening_events` path.
 *
 * Append-only: [writePayload] inserts on first write and, on an idempotent re-upsert of the same
 * id, advances only `revision`/`updatedAt`/`clientOpId` — domain fields are never mutated.
 * Enrichment (display name, book card) is NOT stored; clients join their local `public_profiles`
 * and book mirrors at read time, so a later rename is reflected instead of frozen at record time.
 */
class ActivitySyncRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    override val driver: SqlDriver,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<ActivitySyncPayload, String>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.ACTIVITIES,
        clock = clock,
    ) {
    override fun idAsString(id: String): String = id

    override val ActivitySyncPayload.id: String
        get() = this.id

    override fun ActivitySyncPayload.revisionOf(): Long = revision

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.activitiesQueries].
     * Global (non-userScoped) shape — only the base variants; the *ForUser variants stay unimplemented
     * (they default-throw and are never reached because [userScoped] is false).
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.activitiesQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.activitiesQueries
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
                db.activitiesQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision ?: 0L) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.activitiesQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision ?: 0L) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): ActivitySyncPayload? =
        db.activitiesQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toSyncPayload()

    override fun readPayloads(idStrs: List<String>): List<ActivitySyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.activitiesQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toSyncPayload() }
    }

    /** Tombstone projection — see [SqlSyncableRepository.minimizeTombstone]. */
    override fun minimizeTombstone(payload: ActivitySyncPayload): ActivitySyncPayload =
        payload.copy(
            userId = "",
            type = "",
            bookId = null,
            isReread = false,
            durationMs = 0L,
            milestoneValue = 0,
            milestoneUnit = null,
            shelfId = null,
            shelfName = null,
            occurredAt = 0L,
        )

    override fun writePayload(
        value: ActivitySyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.activitiesQueries.updateSyncColumns(
                revision = rev,
                updated_at = now,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.activitiesQueries.insertSyncable(
                id = value.id,
                user_id = value.userId,
                type = value.type,
                created_at = value.createdAt,
                occurred_at = value.occurredAt,
                book_id = value.bookId,
                is_reread = if (value.isReread) 1L else 0L,
                duration_ms = value.durationMs,
                milestone_value = value.milestoneValue.toLong(),
                milestone_unit = value.milestoneUnit,
                shelf_id = value.shelfId,
                shelf_name = value.shelfName,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    private fun Activities.toSyncPayload(): ActivitySyncPayload =
        ActivitySyncPayload(
            id = id,
            userId = user_id,
            type = type,
            bookId = book_id,
            isReread = is_reread == 1L,
            durationMs = duration_ms,
            milestoneValue = milestone_value.toInt(),
            milestoneUnit = milestone_unit,
            shelfId = shelf_id,
            shelfName = shelf_name,
            occurredAt = occurred_at,
            revision = revision ?: 0L,
            createdAt = created_at,
            updatedAt = updated_at,
            deletedAt = deleted_at,
        )

    private companion object {
        /**
         * Chunk size for `IN (…)` batch reads. Stays under SQLite's conservative 999-variable default
         * cap (raised to 32,766 in 3.32+), so it is safe on any build.
         */
        const val SQLITE_IN_CHUNK = 900
    }
}
