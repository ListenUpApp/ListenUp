package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.Tags
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for tags — the **template** every other aggregate
 * copies during the Exposed → SQLDelight cutover.
 *
 * Handles the full tag aggregate: read/write of [Tag] via the generated
 * [ListenUpDatabase.tagsQueries], including [Tag.slug] which is the canonical
 * URL-safe identity for each tag. Slug is written on insert and included in every
 * payload read.
 *
 * The base [SqlSyncableRepository] owns the revision-bump / timestamp /
 * created-vs-updated / emit-after-commit orchestration; this class supplies only the
 * tag-shaped pieces:
 *  - [substrate] — the [SyncableSubstrateQueries] adapter over `tagsQueries`
 *  - [readPayload] / [readPayloads] — root-row reads by id
 *  - [writePayload] — insert-or-update inside the open transaction
 *  - `Tag.id`
 *
 * Service-layer helpers beyond the base substrate:
 *  - [findById] — fetch one non-deleted tag by id
 *  - [findByIds] — batch fetch non-deleted tags by id (the N+1 fix for `listTagsForBook`)
 *  - [findBySlug] — fetch one non-deleted tag by slug (the stable URL identity)
 *  - [listAll] — fetch all non-deleted tags, ordered by name
 *  - [updateName] — rename a tag (updates [Tag.name] only; slug is preserved)
 */
class TagRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<Tag, String>(db, bus, registry, SyncDomains.TAGS, clock) {
    override val Tag.id: String get() = this.id

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.tagsQueries].
     *
     * This is the canonical adapter shape every aggregate copies: a thin object that
     * forwards each substrate contract method to the matching generated query, mapping
     * the generated revision-cursor rows into the engine-neutral [IdRev]. The generated
     * `existsById` / `selectIdsAboveRevision` / `selectIdRevAtMost` return `Query<T>`, so
     * each call ends in `.executeAsOne()` / `.executeAsList()`; `softDeleteById` returns
     * the affected-row count via SQLDelight's `QueryResult<Long>.value`.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.tagsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.tagsQueries
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
                db.tagsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.tagsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): Tag? =
        db.tagsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toTag()

    override fun readPayloads(idStrs: List<String>): List<Tag> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.tagsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toTag() }
    }

    override fun writePayload(
        value: Tag,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.tagsQueries.update(
                name = value.name,
                slug = value.slug,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.tagsQueries.insert(
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
     * Returns the non-deleted tag with [id], or null when absent or tombstoned.
     */
    suspend fun findById(id: String): Tag? =
        suspendTransaction(db) {
            db.tagsQueries
                .selectById(id)
                .executeAsOneOrNull()
                ?.takeIf { it.deleted_at == null }
                ?.toTag()
        }

    /**
     * Returns the non-deleted tags whose ids appear in [ids], in the same order as
     * [ids] and skipping ids that are absent or tombstoned.
     *
     * This is the batched read that replaces the per-id `findById` loop in
     * `TagServiceImpl.listTagsForBook` — one round-trip (per 900-id chunk) instead of
     * one per junction row.
     */
    suspend fun findByIds(ids: List<String>): List<Tag> {
        if (ids.isEmpty()) return emptyList()
        return suspendTransaction(db) {
            val byId =
                ids
                    .chunked(SQLITE_IN_CHUNK)
                    .flatMap { chunk -> db.tagsQueries.selectByIds(chunk).executeAsList() }
                    .filter { it.deleted_at == null }
                    .associateBy { it.id }
            ids.mapNotNull { byId[it]?.toTag() }
        }
    }

    /**
     * Returns the non-deleted tag whose [Tag.slug] matches [slug], or null when
     * absent or tombstoned. Slugs are normalized to lowercase at creation time;
     * this lookup is inherently case-insensitive.
     */
    suspend fun findBySlug(slug: String): Tag? =
        suspendTransaction(db) {
            db.tagsQueries
                .selectBySlug(slug)
                .executeAsOneOrNull()
                ?.toTag()
        }

    /**
     * Returns all non-deleted tags ordered by name ascending.
     */
    suspend fun listAll(): List<Tag> =
        suspendTransaction(db) {
            db.tagsQueries
                .listAll()
                .executeAsList()
                .map { it.toTag() }
        }

    /**
     * Updates the [Tag.name] of the tag with [id] to [newName]. [Tag.slug] is
     * intentionally preserved — the slug is the stable URL identity and must
     * not change on rename.
     *
     * Bumps revision and publishes a [com.calypsan.listenup.api.sync.SyncEvent.Updated]
     * to the change bus so clients receive the renamed payload. Returns the
     * updated [Tag], or null when no non-deleted row with [id] exists.
     */
    suspend fun updateName(
        id: String,
        newName: String,
    ): Tag? {
        val current = findById(id) ?: return null
        val updated = current.copy(name = newName)
        return when (val result = upsert(updated)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> null
        }
    }

    /** Maps a generated [Tags] row to the wire [Tag] DTO. */
    private fun Tags.toTag(): Tag =
        Tag(
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
