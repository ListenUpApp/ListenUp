package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.BookSeriesTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableRepository
import java.util.UUID
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
 * Syncable-domain repository for book series (Books-B1).
 *
 * Single-table domain. `domainName` is `"series"` — distinct from the table
 * name `book_series`. `idAsString(SeriesId) = id.value` is load-bearing.
 * Series are created by the scanner through [resolveOrCreate]; there is no
 * series write API in B1.
 */
class SeriesRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<SeriesSyncPayload, SeriesId>(
        db = db,
        table = BookSeriesTable,
        bus = bus,
        registry = registry,
        domainName = "series",
        clock = clock,
    ) {
    override val elementSerializer: KSerializer<SeriesSyncPayload> = SeriesSyncPayload.serializer()

    override fun idAsString(id: SeriesId): String = id.value

    override val SeriesSyncPayload.id: SeriesId
        get() = SeriesId(this.id)

    override fun SeriesSyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): SeriesSyncPayload? =
        BookSeriesTable
            .selectAll()
            .where { BookSeriesTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                SeriesSyncPayload(
                    id = row[BookSeriesTable.id],
                    name = row[BookSeriesTable.name],
                    sortName = row[BookSeriesTable.sortName],
                    revision = row[BookSeriesTable.revision],
                    updatedAt = row[BookSeriesTable.updatedAt],
                    createdAt = row[BookSeriesTable.createdAt],
                    deletedAt = row[BookSeriesTable.deletedAt],
                    asin = row[BookSeriesTable.asin],
                    description = row[BookSeriesTable.description],
                    coverPath = row[BookSeriesTable.coverPath],
                    coverBlurHash = row[BookSeriesTable.coverBlurHash],
                )
            }

    override suspend fun writePayload(
        value: SeriesSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        val normalized = normalizeForDedup(value.name)
        if (existed) {
            BookSeriesTable.update({ BookSeriesTable.id eq value.id }) { stmt ->
                stmt[BookSeriesTable.normalizedName] = normalized
                stmt[BookSeriesTable.name] = value.name
                stmt[BookSeriesTable.sortName] = value.sortName
                stmt[BookSeriesTable.asin] = value.asin
                stmt[BookSeriesTable.description] = value.description
                stmt[BookSeriesTable.coverPath] = value.coverPath
                stmt[BookSeriesTable.coverBlurHash] = value.coverBlurHash
                stmt[BookSeriesTable.revision] = rev
                stmt[BookSeriesTable.updatedAt] = now
                stmt[BookSeriesTable.deletedAt] = null
                stmt[BookSeriesTable.clientOpId] = clientOpId
            }
        } else {
            BookSeriesTable.insert { stmt ->
                stmt[BookSeriesTable.id] = value.id
                stmt[BookSeriesTable.normalizedName] = normalized
                stmt[BookSeriesTable.name] = value.name
                stmt[BookSeriesTable.sortName] = value.sortName
                stmt[BookSeriesTable.asin] = value.asin
                stmt[BookSeriesTable.description] = value.description
                stmt[BookSeriesTable.coverPath] = value.coverPath
                stmt[BookSeriesTable.coverBlurHash] = value.coverBlurHash
                stmt[BookSeriesTable.revision] = rev
                stmt[BookSeriesTable.createdAt] = now
                stmt[BookSeriesTable.updatedAt] = now
                stmt[BookSeriesTable.deletedAt] = null
                stmt[BookSeriesTable.clientOpId] = clientOpId
            }
        }
    }

    /**
     * Finds the series whose name shares [name]'s normalized form, or creates
     * one through the substrate's `upsert` (bumping the domain revision and
     * publishing `SyncEvent.Created`). Idempotent on the normalized name; the
     * display name preserves the first writer's casing.
     *
     * The find-miss → create window is a benign race only under SQLite's
     * single-writer model; the single-threaded scan never triggers it.
     */
    suspend fun resolveOrCreate(name: String): SeriesId {
        val normalized = normalizeForDedup(name)
        val existing =
            suspendTransaction(db) {
                BookSeriesTable
                    .selectAll()
                    .where { BookSeriesTable.normalizedName eq normalized }
                    .firstOrNull()
                    ?.get(BookSeriesTable.id)
            }
        if (existing != null) return SeriesId(existing)

        val id = SeriesId(UUID.randomUUID().toString())
        upsert(
            SeriesSyncPayload(
                id = id.value,
                name = name,
                sortName = null,
                revision = 0L,
                updatedAt = 0L,
                createdAt = 0L,
                deletedAt = null,
            ),
            clientOpId = null,
        )
        return id
    }

    /** Reads a series by raw id outside substrate orchestration — test/diagnostic use. */
    suspend fun findById(idStr: String): SeriesSyncPayload? = suspendTransaction(db) { readPayload(idStr) }

    /**
     * Returns the raw id strings of all non-tombstoned series.
     *
     * Used by [com.calypsan.listenup.server.scheduler.OrphanImageCleanupTask] to
     * determine which series cover image files on disk still have a live entity.
     * Tombstoned rows (`deletedAt IS NOT NULL`) are excluded — their images are
     * eligible for cleanup.
     */
    suspend fun listLiveIds(): Set<String> =
        suspendTransaction(db) {
            BookSeriesTable
                .selectAll()
                .where { BookSeriesTable.deletedAt.isNull() }
                .mapTo(HashSet()) { it[BookSeriesTable.id] }
        }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: SeriesId): String = idAsString(id)
}
