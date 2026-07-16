package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Moods
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for moods — the affective twin of [TagRepository].
 *
 * Handles the full mood aggregate: read/write of [Mood] via the generated
 * [ListenUpDatabase.moodsQueries], including [Mood.slug] which is the canonical
 * URL-safe identity for each mood. Slug is written on insert and included in every
 * payload read.
 *
 * The base [SqlSyncableRepository] owns the revision-bump / timestamp /
 * created-vs-updated / emit-after-commit orchestration; this class supplies only the
 * mood-shaped pieces:
 *  - [substrate] — the [SyncableSubstrateQueries] adapter over `moodsQueries`
 *  - [readPayload] / [readPayloads] — root-row reads by id
 *  - [writePayload] — insert-or-update inside the open transaction
 *  - `Mood.id`
 *
 * Service-layer helpers beyond the base substrate:
 *  - [findById] — fetch one non-deleted mood by id
 *  - [findByIds] — batch fetch non-deleted moods by id (the N+1 fix for `listMoodsForBook`)
 *  - [findBySlug] — fetch one non-deleted mood by slug (the stable URL identity)
 *  - [listAll] — fetch all non-deleted moods, ordered by name
 *  - [updateName] — rename a mood (updates [Mood.name] only; slug is preserved)
 */
class MoodRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<Mood, String>(db, bus, registry, SyncDomains.MOODS, clock) {
    override val Mood.id: String get() = this.id

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.moodsQueries].
     *
     * Mirrors the [TagRepository] adapter shape exactly: a thin object that forwards each
     * substrate contract method to the matching generated query, mapping the generated
     * revision-cursor rows into the engine-neutral [IdRev].
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.moodsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.moodsQueries
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
                db.moodsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.moodsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): Mood? =
        db.moodsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toMood()

    override fun readPayloads(idStrs: List<String>): List<Mood> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.moodsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toMood() }
    }

    override fun writePayload(
        value: Mood,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.moodsQueries.update(
                name = value.name,
                slug = value.slug,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.moodsQueries.insert(
                id = value.id,
                name = value.name,
                slug = value.slug,
                revision = rev,
                created_at = now,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /**
     * Returns the non-deleted mood with [id], or null when absent or tombstoned.
     */
    suspend fun findById(id: String): Mood? =
        suspendTransaction(db) {
            db.moodsQueries
                .selectById(id)
                .executeAsOneOrNull()
                ?.takeIf { it.deleted_at == null }
                ?.toMood()
        }

    /**
     * Returns the non-deleted moods whose ids appear in [ids], in the same order as
     * [ids] and skipping ids that are absent or tombstoned.
     *
     * This is the batched read that replaces the per-id `findById` loop in
     * `MoodServiceImpl.listMoodsForBook` — one round-trip (per 900-id chunk) instead of
     * one per junction row.
     */
    suspend fun findByIds(ids: List<String>): List<Mood> {
        if (ids.isEmpty()) return emptyList()
        return suspendTransaction(db) {
            val byId =
                ids
                    .chunked(SQLITE_IN_CHUNK)
                    .flatMap { chunk -> db.moodsQueries.selectByIds(chunk).executeAsList() }
                    .filter { it.deleted_at == null }
                    .associateBy { it.id }
            ids.mapNotNull { byId[it]?.toMood() }
        }
    }

    /**
     * Returns the non-deleted mood whose [Mood.slug] matches [slug], or null when
     * absent or tombstoned. Slugs are normalized to lowercase at creation time;
     * this lookup is inherently case-insensitive.
     */
    suspend fun findBySlug(slug: String): Mood? =
        suspendTransaction(db) {
            db.moodsQueries
                .selectBySlug(slug)
                .executeAsOneOrNull()
                ?.toMood()
        }

    /**
     * Returns all non-deleted moods ordered by name ascending.
     */
    suspend fun listAll(): List<Mood> =
        suspendTransaction(db) {
            db.moodsQueries
                .listAll()
                .executeAsList()
                .map { it.toMood() }
        }

    /**
     * Updates the [Mood.name] of the mood with [id] to [newName]. [Mood.slug] is
     * intentionally preserved — the slug is the stable URL identity and must
     * not change on rename.
     *
     * Bumps revision and publishes a [com.calypsan.listenup.api.sync.SyncEvent.Updated]
     * to the change bus so clients receive the renamed payload. Returns the
     * updated [Mood], or null when no non-deleted row with [id] exists.
     */
    suspend fun updateName(
        id: String,
        newName: String,
    ): Mood? {
        val current = findById(id) ?: return null
        val updated = current.copy(name = newName)
        return when (val result = upsert(updated)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> null
        }
    }

    /** Maps a generated [Moods] row to the wire [Mood] DTO. */
    private fun Moods.toMood(): Mood =
        Mood(
            id = id,
            name = name,
            slug = slug,
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
