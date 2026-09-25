package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.db.sqldelight.Book_series
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.sync.FrameCapture
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import kotlin.uuid.Uuid
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext

private val log = loggerFor<SeriesRepository>()

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
     * A dedup hit on a TOMBSTONED row first follows its `merged_into` redirect chain to the first
     * live series (a merge must survive a rescan); only a hit with no live redirect — a series
     * purged by [OrphanParentPurger], or one whose redirect target is itself dead — is revived in
     * place.
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
            return if (existing.deleted_at == null) {
                SeriesId(existing.id)
            } else {
                resolveTombstonedHit(existing.id, existing.merged_into)
            }
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
     * Tombstoned hits follow their merge redirect, else are revived; see [resolveOrCreate].
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
        // A tombstoned hit follows its merge redirect to the first live series; only a hit with no
        // live redirect (an orphan purge, or a dead redirect) is revived in place.
        val existing = LinkedHashMap<String, SeriesId>(existingRows.size)
        for (row in existingRows) {
            existing[row.normalized_name] =
                if (row.deleted_at == null) {
                    SeriesId(row.id)
                } else {
                    resolveTombstonedHit(row.id, row.merged_into)
                }
        }

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

    /**
     * Resolves a tombstoned dedup hit at [id] (whose row carries [mergedInto]): follows the merge
     * redirect to the first live series when one exists, else revives the row in place under its
     * own id. [resolveOrCreate] and [resolveOrCreateAll] both route every tombstoned hit through
     * this single helper, so the single- and batch-resolution paths can never disagree.
     */
    private suspend fun resolveTombstonedHit(
        id: String,
        mergedInto: String?,
    ): SeriesId = followRedirect(mergedInto) ?: SeriesId(id).also { reviveTombstonedHit(id) }

    /**
     * Walks a merge-redirect chain from [firstHop] to the first LIVE series, or null when the chain
     * ends on a dead or missing row. Chains are walked rather than flattened at merge time so that
     * undoing a middle merge re-routes the names that were merged into it. Relies on the invariant
     * that a LIVE row always carries `merged_into = NULL` — `Series.sq`'s `update` clears it
     * unconditionally, and only [softDeleteMergedInto]'s tombstone ever sets it — so the first live
     * row the walk reaches is unambiguously the answer. Bounded, and guarded against a cycle, so a
     * corrupt chain can never hang a scan; either case is logged, since a silent fallback would hide
     * data corruption rather than surface it.
     */
    private suspend fun followRedirect(firstHop: String?): SeriesId? {
        firstHop ?: return null
        return suspendTransaction(db) {
            val seen = HashSet<String>()
            var hop: String? = firstHop
            while (hop != null) {
                if (!seen.add(hop) || seen.size > MAX_REDIRECT_HOPS) {
                    log.warn {
                        "series redirect chain from $firstHop is cyclic or exceeds $MAX_REDIRECT_HOPS hops; " +
                            "reviving the original"
                    }
                    return@suspendTransaction null
                }
                val row = db.seriesQueries.selectById(hop).executeAsOneOrNull() ?: return@suspendTransaction null
                if (row.deleted_at == null) return@suspendTransaction SeriesId(row.id)
                hop = row.merged_into
            }
            null
        }
    }

    /**
     * Brings [id] back under its own id — merge undo. The base `upsert` bumps the revision and
     * publishes `Updated`; [writePayload]'s update branch clears both `deleted_at` and the merge
     * redirect. Re-upserting a series that is already live is harmless.
     */
    suspend fun revive(id: SeriesId): AppResult<Unit> {
        val payload =
            findById(id.value)
                ?: return AppResult.Failure(SeriesError.NotFound(debugInfo = "series=${id.value}"))
        return upsert(payload.copy(deletedAt = null), clientOpId = null).map { }
    }

    /**
     * Merge-specific tombstone: soft-deletes the merged-away [source] AND records the server-only
     * `merged_into` redirect to [target] in one UPDATE, so a merged-away series can never exist
     * without the redirect that scan-time name resolution follows. Revision bump, timestamping, the
     * [SyncEvent.Deleted] publication, and the [FirehoseSuppressed]/[FrameCapture] gates mirror the
     * base [SqlSyncableRepository.softDelete] exactly — `merged_into` never crosses the wire.
     */
    suspend fun softDeleteMergedInto(
        source: SeriesId,
        target: SeriesId,
    ): AppResult<Unit> {
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null
        val capture = currentCoroutineContext()[FrameCapture.Key]
        val result =
            suspendTransaction(db) {
                val rev = nextRevision()
                val now = clock.now().toEpochMilliseconds()
                val rowsAffected =
                    db.seriesQueries
                        .softDeleteMergedIntoById(
                            revision = rev,
                            updated_at = now,
                            deleted_at = now,
                            client_op_id = null,
                            merged_into = target.value,
                            id = source.value,
                        ).value
                if (rowsAffected == 0L) {
                    AppResult.Failure(SyncError.NotFound(domain = domainName, entityId = source.value))
                } else {
                    val event =
                        SyncEvent.Deleted(
                            id = source.value,
                            revision = rev,
                            occurredAt = now,
                            clientOpId = null,
                        )
                    if (!suppressed) {
                        emitAfterCommit(event = event)
                    } else {
                        log.debug { "change suppressed (firehose): domain=$domainName id=${source.value}" }
                    }
                    AppResult.Success(event)
                }
            }
        if (capture != null && !suppressed && result is AppResult.Success) {
            capture.add(toSyncFrame(result.data))
        }
        return result.map { }
    }

    /** Reads a series by raw id outside substrate orchestration — test/diagnostic use. */
    suspend fun findById(idStr: String): SeriesSyncPayload? = suspendTransaction(db) { readPayload(idStr) }

    /**
     * Returns the raw id strings of all non-tombstoned series. Tombstoned rows
     * (`deletedAt IS NOT NULL`) are excluded.
     */
    suspend fun listLiveIds(): Set<String> =
        suspendTransaction(db) {
            db.seriesQueries
                .selectLiveIds()
                .executeAsList()
                .toHashSet()
        }

    /**
     * Returns the stored `coverPath` of every non-tombstoned series that has one — the set of
     * cover files still in use.
     *
     * Used by [com.calypsan.listenup.server.scheduler.OrphanImageCleanupTask]. Covers are
     * content-addressed (`series/<sha>.jpg`), so liveness is "a live row points at this file",
     * never "the filename is a live id".
     */
    suspend fun listLiveCoverPaths(): Set<String> =
        suspendTransaction(db) {
            db.seriesQueries
                .selectLiveCoverPaths()
                .executeAsList()
                .filterNotNullTo(HashSet())
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

        /** Upper bound on a merge-redirect walk; real chains are a handful of hops at most. */
        const val MAX_REDIRECT_HOPS = 32
    }
}
