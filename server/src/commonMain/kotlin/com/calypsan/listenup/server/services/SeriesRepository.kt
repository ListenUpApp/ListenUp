package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.sqldelight.Book_series
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import kotlin.uuid.Uuid
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for book series (Books-B1, SQLDelight cutover).
 *
 * Single-table syncable domain. `domainName` is `"series"` — distinct from the
 * table name `book_series`. The base [SqlSyncableRepository] owns revision bumping,
 * timestamping, created-vs-updated discrimination, and change-bus publication; this
 * class supplies the series-shaped pieces (substrate adapter, read/write, serializer,
 * id/revision projections).
 *
 * `idAsString(SeriesId) = id.value` is load-bearing — the base's default `toString()`
 * on a value class would corrupt every column the id is written to. Series are created
 * by the scanner through [resolveOrCreate]; there is no series write API in B1.
 */
class SeriesRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<SeriesSyncPayload, SeriesId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.SERIES,
        clock = clock,
    ) {
    override fun idAsString(id: SeriesId): String = id.value

    override val SeriesSyncPayload.id: SeriesId
        get() = SeriesId(this.id)

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.seriesQueries].
     * Mirrors the canonical [com.calypsan.listenup.server.sync.TagRepository] shape.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.seriesQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.seriesQueries
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
                db.seriesQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.seriesQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): SeriesSyncPayload? =
        db.seriesQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toPayload()

    override fun readPayloads(idStrs: List<String>): List<SeriesSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch in chunks of 900 and preserve the requested order.
        val byId =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.seriesQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        return idStrs.mapNotNull { byId[it]?.toPayload() }
    }

    override fun writePayload(
        value: SeriesSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        val normalized = normalizeForDedup(value.name)
        if (existed) {
            db.seriesQueries.update(
                normalized_name = normalized,
                name = value.name,
                sort_name = value.sortName,
                asin = value.asin,
                description = value.description,
                cover_path = value.coverPath,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            db.seriesQueries.insert(
                id = value.id,
                normalized_name = normalized,
                name = value.name,
                sort_name = value.sortName,
                revision = rev,
                created_at = now,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                asin = value.asin,
                description = value.description,
                cover_path = value.coverPath,
            )
        }
    }

    /**
     * Finds the series whose name shares [name]'s normalized form, or creates
     * one through the base's `upsert` (bumping the domain revision and
     * publishing `SyncEvent.Created`). Idempotent on the normalized name; the
     * display name preserves the first writer's casing.
     *
     * A dedup hit on a TOMBSTONED row (a series purged by [OrphanParentPurger] after its last
     * live book was removed) is revived in place — `deleted_at` cleared, revision bumped,
     * `SyncEvent.Updated` published — so re-ingesting the same name resurrects the series under
     * its original id. Live hits remain pure reads: no event, no revision bump.
     *
     * The find-miss → create window is a benign race only under SQLite's
     * single-writer model; the single-threaded scan never triggers it.
     */
    suspend fun resolveOrCreate(name: String): SeriesId {
        val normalized = normalizeForDedup(name)
        val existing =
            suspendTransaction(db) {
                db.seriesQueries
                    .selectByNormalizedName(normalized)
                    .executeAsOneOrNull()
            }
        if (existing != null) {
            if (existing.deleted_at != null) reviveTombstonedHit(existing.id)
            return SeriesId(existing.id)
        }

        val id = SeriesId(Uuid.random().toString())
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

    /**
     * Batch counterpart to [resolveOrCreate]: resolves a whole scan's series in one pass.
     *
     * The per-book [resolveOrCreate] storm — one SELECT (and a create txn for each new name) per
     * series per book — collapses to a single bulk SELECT here, run ONCE before the persist loop.
     * Given the full collection of series [names] for the scan (duplicates allowed), this:
     *
     *  1. computes each name's dedup key exactly as [resolveOrCreate] does — `normalizeForDedup(name)`;
     *  2. SELECTs every existing row whose key is in the unique-key set in **one** query
     *     ([selectByNormalizedNames], chunked under SQLite's variable limit);
     *  3. creates the missing keys through the same [resolveOrCreate] (which bumps the domain
     *     revision and publishes the identical `SyncEvent.Created`), so a brand-new series' sync
     *     semantics are byte-identical to the single-resolution path.
     *
     * @return a `Map<normalizedKey, SeriesId>` keyed by [normalizeForDedup] — callers look an id up
     *   by recomputing that key for each book's series. Every supplied name's key is present.
     *
     * Tombstoned hits are revived; see [resolveOrCreate].
     */
    suspend fun resolveOrCreateAll(names: Collection<String>): Map<String, SeriesId> {
        if (names.isEmpty()) return emptyMap()
        // Normalized key → a representative display name. First writer wins, matching
        // resolveOrCreate's first-casing-wins semantics for the create path.
        val byKey = LinkedHashMap<String, String>()
        for (name in names) {
            val key = normalizeForDedup(name)
            if (key !in byKey) byKey[key] = name
        }

        // One bulk SELECT for the existing rows — the bulk of the work, collapsed from N per-book reads.
        val existingRows =
            suspendTransaction(db) {
                byKey.keys
                    .chunked(SQLITE_IN_CHUNK)
                    .flatMap { chunk -> db.seriesQueries.selectByNormalizedNames(chunk).executeAsList() }
            }
        // Revive tombstoned dedup hits before handing their ids back: a purged series returned by
        // the dedup lookup must come back live (see reviveTombstonedHit). Rare — only ever after an
        // orphan purge — so per-id upserts are fine here.
        for (row in existingRows) {
            if (row.deleted_at != null) reviveTombstonedHit(row.id)
        }
        val existing = existingRows.associate { it.normalized_name to SeriesId(it.id) }

        val resolved = LinkedHashMap<String, SeriesId>(byKey.size)
        for ((key, name) in byKey) {
            resolved[key] = existing[key] ?: resolveOrCreate(name)
        }
        return resolved
    }

    /**
     * Revives a tombstoned dedup hit in place: re-upserts the row's own read-back payload with
     * `deletedAt = null`. The base `upsert` bumps the domain revision and publishes
     * [com.calypsan.listenup.api.sync.SyncEvent.Updated]; [writePayload]'s update branch always
     * clears `deleted_at`. The id stays stable, so junction rows written against it resolve again —
     * the same revive semantics as [BookRepository.reviveById] (clear deleted_at + bump revision +
     * publish Updated), composed from the existing substrate instead of a dedicated query.
     * Enrichment columns survive because the payload is the row's own current content.
     */
    private suspend fun reviveTombstonedHit(idStr: String) {
        val payload = findById(idStr) ?: return
        upsert(payload.copy(deletedAt = null), clientOpId = null)
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
            db.seriesQueries
                .selectLiveIds()
                .executeAsList()
                .toHashSet()
        }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: SeriesId): String = idAsString(id)

    /**
     * Returns `(id, revision)` pairs for the LIVE rows in the series table (soft-deleted
     * excluded), with no revision filter. Exists solely to feed the cross-stack digest
     * parity test (`DigestParityE2ETest` in `:app:sharedLogic:jvmTest`) so the client
     * [com.calypsan.listenup.client.data.sync.DigestComputer] can be driven over the
     * identical row set the server [digest] covers — which, since F1, is LIVE rows only
     * (tombstones excluded, symmetric with the client's tombstone-excluding digest).
     *
     * Intentionally public (not internal) because the test lives in a different Gradle
     * module (`:app:sharedLogic`) and Kotlin's `internal` does not cross module boundaries.
     */
    suspend fun allIdRevisionsForTest(): List<Pair<String, Long>> =
        suspendTransaction(db) {
            db.seriesQueries
                .selectAllIdRevisions()
                .executeAsList()
                .map { it.id to it.revision }
        }

    /** Maps a generated [Book_series] row to the wire [SeriesSyncPayload] DTO. */
    private fun Book_series.toPayload(): SeriesSyncPayload =
        SeriesSyncPayload(
            id = id,
            name = name,
            sortName = sort_name,
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
            asin = asin,
            description = description,
            coverPath = cover_path,
        )

    private companion object {
        /**
         * Chunk size for `IN (…)` batch reads. Kept under SQLite's default
         * `SQLITE_MAX_VARIABLE_NUMBER` (999) with headroom for any fixed bind params.
         */
        const val SQLITE_IN_CHUNK = 900
    }
}
