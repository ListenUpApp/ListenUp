package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.db.sqldelight.Contributors
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.scanner.pipeline.SortKeys
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.IdRev
import com.calypsan.listenup.server.sync.SqlSyncableRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableSubstrateQueries
import kotlin.uuid.Uuid
import kotlin.time.Clock

/**
 * SQLDelight syncable repository for contributors (Books-B1, SQLDelight cutover).
 *
 * Single-table syncable domain with one child table — `contributor_aliases` — that
 * backs the wire payload's embedded `aliases: List<String>` field. The base
 * [SqlSyncableRepository] owns revision bumping, timestamping, the
 * created-vs-updated discrimination, and change-bus publication; this class supplies
 * only the contributor-shaped pieces:
 *  - [substrate] — the [SyncableSubstrateQueries] adapter over `contributorsQueries`
 *  - [readPayload] / [readPayloads] — root row + alias child rows by id
 *  - [writePayload] — insert-or-update root row, then replace the alias set
 *  - `ContributorSyncPayload.id` / `revisionOf`
 *
 * `idAsString(ContributorId) = id.value` is load-bearing — the base's default
 * `toString()` on a value class returns `"ContributorId(value=foo)"`, which would
 * corrupt every column the id is written to.
 *
 * Contributors are created by the scanner through [resolveOrCreate], not by a
 * client write — there is no contributor write API in B1.
 */
class ContributorRepository(
    db: ListenUpDatabase,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SqlSyncableRepository<ContributorSyncPayload, ContributorId>(
        db = db,
        bus = bus,
        registry = registry,
        key = SyncDomains.CONTRIBUTORS,
        clock = clock,
    ) {
    override fun idAsString(id: ContributorId): String = id.value

    override val ContributorSyncPayload.id: ContributorId
        get() = ContributorId(this.id)

    override fun ContributorSyncPayload.revisionOf(): Long = revision

    /**
     * [SyncableSubstrateQueries] adapter over the generated [ListenUpDatabase.contributorsQueries].
     * Mirrors the canonical [com.calypsan.listenup.server.sync.TagRepository] shape.
     */
    override val substrate: SyncableSubstrateQueries =
        object : SyncableSubstrateQueries {
            override fun existsById(id: String): Boolean = db.contributorsQueries.existsById(id).executeAsOne()

            override fun softDeleteById(
                id: String,
                revision: Long,
                updatedAt: Long,
                deletedAt: Long,
                clientOpId: String?,
            ): Long =
                db.contributorsQueries
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
                db.contributorsQueries
                    .selectIdsAboveRevision(cursor, limit) { id, revision -> IdRev(id, revision) }
                    .executeAsList()

            override fun selectIdRevAtMost(cursor: Long): List<IdRev> =
                db.contributorsQueries
                    .selectIdRevAtMost(cursor) { id, revision -> IdRev(id, revision) }
                    .executeAsList()
        }

    override fun readPayload(idStr: String): ContributorSyncPayload? =
        db.contributorsQueries
            .selectById(idStr)
            .executeAsOneOrNull()
            ?.toPayload(db.contributorsQueries.aliasesFor(idStr).executeAsList())

    override fun readPayloads(idStrs: List<String>): List<ContributorSyncPayload> {
        if (idStrs.isEmpty()) return emptyList()
        // SQLite's variable limit (SQLITE_MAX_VARIABLE_NUMBER, 999 by default) caps an
        // `IN (?, ?, …)` list, so batch root rows and alias rows in chunks of 900 and
        // preserve the requested order. One round-trip per chunk replaces the per-id N+1.
        val rowsById =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.contributorsQueries.selectByIds(chunk).executeAsList() }
                .associateBy { it.id }
        val aliasesById =
            idStrs
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.contributorsQueries.aliasesForIds(chunk).executeAsList() }
                .groupBy({ it.contributor_id }, { it.alias })
        return idStrs.mapNotNull { id -> rowsById[id]?.toPayload(aliasesById[id].orEmpty()) }
    }

    override fun writePayload(
        value: ContributorSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            // normalized_name is the dedup key established at INSERT — do not update it.
            // Changing it post-creation could violate the unique index and break in-flight lookups.
            db.contributorsQueries.update(
                name = value.name,
                sort_name = value.sortName,
                asin = value.asin,
                description = value.description,
                image_path = value.imagePath,
                birth_date = value.birthDate,
                death_date = value.deathDate,
                website = value.website,
                revision = rev,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
                id = value.id,
            )
        } else {
            // Must stay in sync with resolveOrCreate's lookup key: both use contributorDedupKey,
            // so a row written via a direct upsert gets the same dedup bucket that resolveOrCreate
            // would compute. value.sortName is always non-null here because resolveOrCreate stores
            // the derived sort name before calling upsert.
            val normalized = contributorDedupKey(value.name, value.sortName)
            db.contributorsQueries.insert(
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
                image_path = value.imagePath,
                birth_date = value.birthDate,
                death_date = value.deathDate,
                website = value.website,
            )
        }
        replaceAliases(value.id, value.aliases)
    }

    /**
     * Atomically replaces the alias set for [contributorId] via delete-then-insert,
     * mirroring the wire payload's embedded array. Called from [writePayload] inside
     * the base's open transaction.
     */
    private fun replaceAliases(
        contributorId: String,
        aliases: List<String>,
    ) {
        db.contributorsQueries.deleteAliasesFor(contributorId)
        aliases.forEach { alias -> db.contributorsQueries.insertAlias(contributorId, alias) }
    }

    /**
     * Finds the contributor whose dedup key matches [contributorDedupKey]`(name, sortName)`,
     * or creates one through the base's `upsert` (which bumps the domain revision and
     * publishes `SyncEvent.Created`). Returns the stable [ContributorId] either way.
     *
     * The dedup key is always `contributorDedupKey(name, sortName)` — the normalized sort name,
     * derived as `"Surname, Given"` when [sortName] is null. This means every creation path
     * (scanner with an explicit sort name, manual edit with null sort name, Audible enrichment
     * with null sort name) buckets the same person identically. "Brandon Sanderson" and
     * "Sanderson, Brandon" both resolve to `"sanderson, brandon"` and share one row.
     *
     * When [sortName] is null, the derived form is stored on the row so that the normalizedName
     * column and the sortName column are consistent regardless of which path creates the row.
     *
     * First display name wins on collision: a subsequent call with a different [name] but the same
     * resolved key finds the existing row and returns its id without updating the stored name.
     *
     * Idempotent: a rescan of unchanged books re-resolves existing contributors with no event and
     * no revision bump.
     *
     * A dedup hit on a TOMBSTONED row (a parent purged by [OrphanParentPurger] after its last
     * live book was removed) is revived in place — `deleted_at` cleared, revision bumped,
     * `SyncEvent.Updated` published — so re-ingesting the same name resurrects the parent under
     * its original id. Live hits remain pure reads: no event, no revision bump.
     *
     * The find-miss → create window is a benign race only under SQLite's single-writer model; the
     * single-threaded scan never triggers it.
     */
    suspend fun resolveOrCreate(
        name: String,
        sortName: String?,
    ): ContributorId {
        val derivedSortName = sortName ?: SortKeys.sortName(name, null)
        val normalized = contributorDedupKey(name, derivedSortName)
        val existing =
            suspendTransaction(db) {
                db.contributorsQueries
                    .selectByNormalizedName(normalized)
                    .executeAsOneOrNull()
            }
        if (existing != null) {
            if (existing.deleted_at != null) reviveTombstonedHit(existing.id)
            return ContributorId(existing.id)
        }

        val id = ContributorId(Uuid.random().toString())
        upsert(
            ContributorSyncPayload(
                id = id.value,
                name = name,
                sortName = derivedSortName,
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
     * Batch counterpart to [resolveOrCreate]: resolves a whole scan's contributors in one pass.
     *
     * The per-book [resolveOrCreate] storm — one SELECT (and a create txn for each new name) per
     * contributor per book — collapses to a single bulk SELECT here, run ONCE before the persist
     * loop. Given the full collection of `(name, sortName?)` identities for the scan (duplicates
     * allowed), this:
     *
     *  1. computes each identity's dedup key exactly as [resolveOrCreate] does — `contributorDedupKey(name,
     *     sortName ?: SortKeys.sortName(name, null))` — so the same person buckets identically;
     *  2. SELECTs every existing row whose key is in the unique-key set in **one** query
     *     ([selectByNormalizedNames], chunked under SQLite's variable limit);
     *  3. creates the missing keys through the same [resolveOrCreate] (which bumps the domain
     *     revision and publishes the identical `SyncEvent.Created`), so a brand-new contributor's
     *     sync semantics are byte-identical to the single-resolution path.
     *
     * @return a `Map<dedupKey, ContributorId>` keyed by [contributorDedupKey] — callers look an
     *   id up by recomputing that key for each book's contributors. Every supplied identity's key
     *   is present in the result.
     *
     * Tombstoned hits are revived; see [resolveOrCreate].
     */
    suspend fun resolveOrCreateAll(identities: Collection<Pair<String, String?>>): Map<String, ContributorId> {
        if (identities.isEmpty()) return emptyMap()
        // Dedup-key → a representative (name, derivedSortName) for that bucket. First writer wins on
        // collision, matching resolveOrCreate's first-display-name-wins semantics for the create path.
        val byKey = LinkedHashMap<String, Pair<String, String>>()
        for ((name, sortName) in identities) {
            val derivedSortName = sortName ?: SortKeys.sortName(name, null)
            val key = contributorDedupKey(name, derivedSortName)
            if (key !in byKey) byKey[key] = name to derivedSortName
        }

        // One bulk SELECT for the existing rows — the bulk of the work, collapsed from N per-book reads.
        val existingRows =
            suspendTransaction(db) {
                byKey.keys
                    .chunked(SQLITE_IN_CHUNK)
                    .flatMap { chunk -> db.contributorsQueries.selectByNormalizedNames(chunk).executeAsList() }
            }
        // Revive tombstoned dedup hits before handing their ids back: a purged parent returned by
        // the dedup lookup must come back live (see reviveTombstonedHit). Rare — only ever after an
        // orphan purge — so per-id upserts are fine here.
        for (row in existingRows) {
            if (row.deleted_at != null) reviveTombstonedHit(row.id)
        }
        val existing = existingRows.associate { it.normalized_name to ContributorId(it.id) }

        val resolved = LinkedHashMap<String, ContributorId>(byKey.size)
        for ((key, identity) in byKey) {
            val id = existing[key] ?: resolveOrCreate(identity.first, identity.second)
            resolved[key] = id
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

    /** Reads a contributor by raw id outside substrate orchestration — test/diagnostic use. */
    suspend fun findById(idStr: String): ContributorSyncPayload? = suspendTransaction(db) { readPayload(idStr) }

    /**
     * Returns the raw id strings of all non-tombstoned contributors.
     *
     * Used by [com.calypsan.listenup.server.scheduler.OrphanImageCleanupTask] to
     * determine which contributor image files on disk still have a live entity.
     * Tombstoned rows (`deletedAt IS NOT NULL`) are excluded — their images are
     * eligible for cleanup.
     */
    suspend fun listLiveIds(): Set<String> =
        suspendTransaction(db) {
            db.contributorsQueries
                .selectLiveIds()
                .executeAsList()
                .toHashSet()
        }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: ContributorId): String = idAsString(id)

    /** Maps a generated [Contributors] root row plus its [aliases] to the wire payload. */
    private fun Contributors.toPayload(aliases: List<String>): ContributorSyncPayload =
        ContributorSyncPayload(
            id = id,
            name = name,
            sortName = sort_name,
            revision = revision,
            updatedAt = updated_at,
            createdAt = created_at,
            deletedAt = deleted_at,
            asin = asin,
            description = description,
            imagePath = image_path,
            birthDate = birth_date,
            deathDate = death_date,
            website = website,
            aliases = aliases,
        )

    private companion object {
        /**
         * Chunk size for `IN (…)` batch reads. Kept under SQLite's default
         * `SQLITE_MAX_VARIABLE_NUMBER` (999) with headroom for any fixed bind params.
         */
        const val SQLITE_IN_CHUNK = 900
    }
}
