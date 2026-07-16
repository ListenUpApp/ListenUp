package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.db.sqldelight.Genres
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import kotlin.time.Clock

/**
 * SQLDelight syncable-domain repository for genres.
 *
 * Single-table substrate — one row per genre. Hierarchy is expressed in the row
 * itself ([Genres.path], [Genres.parent_id], [Genres.depth]); the base
 * [SqlSyncableRepository] owns revision bumping, timestamping, and change-bus
 * publication, while this class supplies the row read/write shape over the
 * generated [ListenUpDatabase.genresQueries].
 *
 * `idAsString(GenreId) = id.value` is load-bearing — the substrate's default
 * `toString()` on a value class returns `"GenreId(value=foo)"`, which would corrupt
 * every column the id is written to.
 *
 * Hierarchy primitives — [descendantIds], [directChildren], [rewritePathPrefix],
 * [updateParentId] — back the move / merge / delete-subtree orchestration in
 * `GenreServiceImpl`. They open their own read/write transaction here; the service
 * issues the same generated queries inside its own open transaction so a whole
 * move runs atomically (the materialized-path rewrite, the parent re-point, and
 * each affected-row re-upsert all commit together).
 */
class GenreRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<GenreSyncPayload, GenreId>(db, bus, registry, SyncDomains.GENRES, clock) {
    override fun idAsString(id: GenreId): String = id.value

    override val GenreSyncPayload.id: GenreId
        get() = GenreId(this.id)

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.genresQueries] —
     * the canonical shape every aggregate copies. Genres are global (cross-user), so the
     * user-scoped substrate variants stay unimplemented (the base never calls them).
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.genresQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.genresQueries
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
                db.genresQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.genresQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): GenreSyncPayload? =
        db.genresQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toPayload()

    override fun writePayload(
        value: GenreSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            db.genresQueries.update(
                name = value.name,
                slug = value.slug,
                path = value.path,
                parent_id = value.parentId,
                depth = value.depth.toLong(),
                sort_order = value.sortOrder.toLong(),
                color = value.color,
                description = value.description,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.genresQueries.insert(
                id = value.id,
                name = value.name,
                slug = value.slug,
                path = value.path,
                parent_id = value.parentId,
                depth = value.depth.toLong(),
                sort_order = value.sortOrder.toLong(),
                color = value.color,
                description = value.description,
                revision = rev,
                created_at = now,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
            )
        }
    }

    /** Reads a genre by raw id outside substrate orchestration — test/diagnostic + service use. */
    suspend fun findById(idStr: String): GenreSyncPayload? = suspendTransaction(db) { readPayload(idStr) }

    /** Returns the subset of [ids] that are live (non-tombstoned) genres, in one query. */
    suspend fun findLiveIds(ids: Collection<String>): Set<String> =
        if (ids.isEmpty()) {
            emptySet()
        } else {
            suspendTransaction(db) {
                db.genresQueries
                    .selectLiveIdsByIds(ids.distinct())
                    .executeAsList()
                    .toSet()
            }
        }

    /**
     * Returns the live (non-tombstoned) genre with the given [slug], or null if absent.
     * Tombstoned rows are excluded — slug uniqueness is enforced only among live rows
     * (V23's `idx_genres_slug_live` partial unique index).
     */
    suspend fun findBySlug(slug: String): GenreSyncPayload? =
        suspendTransaction(db) {
            val id = db.genresQueries.findBySlug(slug).executeAsOneOrNull() ?: return@suspendTransaction null
            readPayload(id)
        }

    /**
     * Returns the live (non-tombstoned) genre at the given materialized [path], or null
     * if absent. Tombstoned rows are excluded.
     */
    suspend fun findByPath(path: String): GenreSyncPayload? =
        suspendTransaction(db) {
            val id = db.genresQueries.findByPath(path).executeAsOneOrNull() ?: return@suspendTransaction null
            readPayload(id)
        }

    /**
     * Counts live (non-tombstoned) genres. Used by
     * [com.calypsan.listenup.server.seed.GenreDomainSeeder] for its run-once idempotency check.
     */
    suspend fun count(): Long = suspendTransaction(db) { db.genresQueries.countLive().executeAsOne() }

    /**
     * Returns the ids of [pathPrefix] itself plus all of its descendants, regardless of
     * tombstone state. Uses the collision-safe pattern `path = ? OR path LIKE ? || '/%'`
     * so `"/fic"` does not match `"/fiction/…"`. Callers that need to filter to live rows
     * chain their own predicate — the cascade operations (move, merge, delete-subtree)
     * need to see tombstoned descendants too.
     */
    suspend fun descendantIds(pathPrefix: String): List<String> =
        suspendTransaction(db) { db.genresQueries.descendantIds(pathPrefix).executeAsList() }

    /**
     * Direct children (live only) of the given parent. Empty when [parentId] has no
     * children or is itself unknown.
     */
    suspend fun directChildren(parentId: String): List<String> =
        suspendTransaction(db) { db.genresQueries.directChildren(parentId).executeAsList() }

    /**
     * Bulk-rewrites the `path` and `depth` columns on the subtree rooted at
     * [oldPathPrefix], replacing the prefix with [newPathPrefix] and shifting `depth`
     * by [depthDelta]. The materialized-path reparent primitive (`moveGenre` /
     * `mergeGenres` build on top). Matches the Exposed semantics exactly:
     * `SET path = ? || SUBSTR(path, LENGTH(?) + 1), depth = depth + ?`.
     */
    suspend fun rewritePathPrefix(
        oldPathPrefix: String,
        newPathPrefix: String,
        depthDelta: Int,
    ) {
        suspendTransaction(db) {
            db.genresQueries.rewritePathPrefix(
                new_prefix = newPathPrefix,
                // substr_from = oldPrefix.length + 1 (1-based SUBSTR start past the old prefix);
                // the query CASTs it to INTEGER at eval time (see Genres.sq).
                substr_from = (oldPathPrefix.length + 1).toString(),
                depth_delta = depthDelta.toLong(),
                old_prefix = oldPathPrefix,
            )
        }
    }

    /**
     * Updates a single genre's `parent_id` to [newParentId] (null clears the pointer,
     * marking the row as a root). Used by `moveGenre` after [rewritePathPrefix] has
     * rewritten the subtree's materialized paths.
     */
    suspend fun updateParentId(
        id: String,
        newParentId: String?,
    ) {
        suspendTransaction(db) { db.genresQueries.updateParentId(parent_id = newParentId, id = id) }
    }

    /** Maps a generated [Genres] row to the [GenreSyncPayload] wire/domain shape. */
    private fun Genres.toPayload(): GenreSyncPayload =
        GenreSyncPayload(
            id = id,
            name = name,
            slug = slug,
            path = path,
            parentId = parent_id,
            depth = depth.toInt(),
            sortOrder = sort_order.toInt(),
            color = color,
            description = description,
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
        )
}
