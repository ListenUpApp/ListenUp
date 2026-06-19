package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableRepository
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Syncable-domain repository for genres.
 *
 * Single-table substrate — one row per genre. Hierarchy is expressed in the row
 * itself ([GenreTable.path], [GenreTable.parentId], [GenreTable.depth]); the
 * substrate ([SyncableRepository]) owns revision bumping, timestamping, and
 * change-bus publication, while this class supplies the row read/write shape.
 *
 * `idAsString(GenreId) = id.value` is load-bearing — the substrate's default
 * `toString()` on a value class returns `"GenreId(value=foo)"`, which would
 * corrupt every column the id is written to.
 *
 * Hierarchy primitives (path rewrite, parent update, descendant lookup) live on
 * [GenreTable]; orchestration (move, merge, delete-subtree) lives in
 * `GenreServiceImpl`. This repository only owns the substrate read/write +
 * three simple lookups (`findBySlug`, `findByPath`, `count`) used by the
 * seeder and by upper-layer services.
 */
class GenreRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<GenreSyncPayload, GenreId>(
        db = db,
        table = GenreTable,
        bus = bus,
        registry = registry,
        domainName = "genres",
        clock = clock,
    ) {
    override val elementSerializer: KSerializer<GenreSyncPayload> = GenreSyncPayload.serializer()

    override fun idAsString(id: GenreId): String = id.value

    override val GenreSyncPayload.id: GenreId
        get() = GenreId(this.id)

    override fun GenreSyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): GenreSyncPayload? =
        GenreTable
            .selectAll()
            .where { GenreTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                GenreSyncPayload(
                    id = row[GenreTable.id],
                    name = row[GenreTable.name],
                    slug = row[GenreTable.slug],
                    path = row[GenreTable.path],
                    parentId = row[GenreTable.parentId],
                    depth = row[GenreTable.depth],
                    sortOrder = row[GenreTable.sortOrder],
                    color = row[GenreTable.color],
                    description = row[GenreTable.description],
                    revision = row[GenreTable.revision],
                    updatedAt = row[GenreTable.updatedAt],
                    createdAt = row[GenreTable.createdAt],
                    deletedAt = row[GenreTable.deletedAt],
                )
            }

    override suspend fun writePayload(
        value: GenreSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            GenreTable.update({ GenreTable.id eq value.id }) { stmt ->
                stmt[GenreTable.name] = value.name
                stmt[GenreTable.slug] = value.slug
                stmt[GenreTable.path] = value.path
                stmt[GenreTable.parentId] = value.parentId
                stmt[GenreTable.depth] = value.depth
                stmt[GenreTable.sortOrder] = value.sortOrder
                stmt[GenreTable.color] = value.color
                stmt[GenreTable.description] = value.description
                stmt[GenreTable.revision] = rev
                stmt[GenreTable.updatedAt] = now
                stmt[GenreTable.deletedAt] = null
                stmt[GenreTable.clientOpId] = clientOpId
            }
        } else {
            GenreTable.insert { stmt ->
                stmt[GenreTable.id] = value.id
                stmt[GenreTable.name] = value.name
                stmt[GenreTable.slug] = value.slug
                stmt[GenreTable.path] = value.path
                stmt[GenreTable.parentId] = value.parentId
                stmt[GenreTable.depth] = value.depth
                stmt[GenreTable.sortOrder] = value.sortOrder
                stmt[GenreTable.color] = value.color
                stmt[GenreTable.description] = value.description
                stmt[GenreTable.revision] = rev
                stmt[GenreTable.createdAt] = now
                stmt[GenreTable.updatedAt] = now
                stmt[GenreTable.deletedAt] = null
                stmt[GenreTable.clientOpId] = clientOpId
            }
        }
    }

    /** Reads a genre by raw id outside substrate orchestration — test/diagnostic use. */
    suspend fun findById(idStr: String): GenreSyncPayload? = suspendTransaction(db) { readPayload(idStr) }

    /**
     * Returns the live (non-tombstoned) genre with the given [slug], or null if
     * absent. Tombstoned rows are excluded — slug uniqueness is enforced only
     * among live rows (V23's `idx_genres_slug_live` partial unique index).
     */
    suspend fun findBySlug(slug: String): GenreSyncPayload? =
        suspendTransaction(db) {
            val id = GenreTable.findBySlug(slug) ?: return@suspendTransaction null
            readPayload(id)
        }

    /**
     * Returns the live (non-tombstoned) genre at the given materialized [path],
     * or null if absent. Tombstoned rows are excluded.
     */
    suspend fun findByPath(path: String): GenreSyncPayload? =
        suspendTransaction(db) {
            val id = GenreTable.findByPath(path) ?: return@suspendTransaction null
            readPayload(id)
        }

    /**
     * Counts live (non-tombstoned) genres. Used by [com.calypsan.listenup.server.seed.GenreDomainSeeder]
     * for its run-once idempotency check.
     */
    suspend fun count(): Long =
        suspendTransaction(db) {
            GenreTable
                .selectAll()
                .where { GenreTable.deletedAt.isNull() }
                .count()
        }
}
