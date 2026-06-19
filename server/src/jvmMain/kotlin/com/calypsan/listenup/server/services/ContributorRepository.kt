package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.db.ContributorAliasTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.scanner.pipeline.SortKeys
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
 * Syncable-domain repository for contributors (Books-B1).
 *
 * Single-table domain — one row per contributor. The substrate
 * ([SyncableRepository]) owns revision bumping, timestamping, and
 * change-bus publication; this class supplies the row read/write shape.
 *
 * `idAsString(ContributorId) = id.value` is load-bearing — the substrate's
 * default `toString()` on a value class returns `"ContributorId(value=foo)"`,
 * which would corrupt every column the id is written to.
 *
 * Contributors are created by the scanner through [resolveOrCreate], not by a
 * client write — there is no contributor write API in B1.
 */
class ContributorRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<ContributorSyncPayload, ContributorId>(
        db = db,
        table = ContributorTable,
        bus = bus,
        registry = registry,
        domainName = "contributors",
        clock = clock,
    ) {
    override val elementSerializer: KSerializer<ContributorSyncPayload> = ContributorSyncPayload.serializer()

    override fun idAsString(id: ContributorId): String = id.value

    override val ContributorSyncPayload.id: ContributorId
        get() = ContributorId(this.id)

    override fun ContributorSyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): ContributorSyncPayload? =
        ContributorTable
            .selectAll()
            .where { ContributorTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                ContributorSyncPayload(
                    id = row[ContributorTable.id],
                    name = row[ContributorTable.name],
                    sortName = row[ContributorTable.sortName],
                    revision = row[ContributorTable.revision],
                    updatedAt = row[ContributorTable.updatedAt],
                    createdAt = row[ContributorTable.createdAt],
                    deletedAt = row[ContributorTable.deletedAt],
                    asin = row[ContributorTable.asin],
                    description = row[ContributorTable.description],
                    imagePath = row[ContributorTable.imagePath],
                    imageBlurHash = row[ContributorTable.imageBlurHash],
                    birthDate = row[ContributorTable.birthDate],
                    deathDate = row[ContributorTable.deathDate],
                    website = row[ContributorTable.website],
                    aliases = ContributorAliasTable.aliasesFor(row[ContributorTable.id]),
                )
            }

    override suspend fun writePayload(
        value: ContributorSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            // normalizedName is the dedup key established at INSERT — do not update it.
            // Changing it post-creation could violate the unique index and break in-flight lookups.
            ContributorTable.update({ ContributorTable.id eq value.id }) { stmt ->
                stmt[ContributorTable.name] = value.name
                stmt[ContributorTable.sortName] = value.sortName
                stmt[ContributorTable.asin] = value.asin
                stmt[ContributorTable.description] = value.description
                stmt[ContributorTable.imagePath] = value.imagePath
                stmt[ContributorTable.imageBlurHash] = value.imageBlurHash
                stmt[ContributorTable.birthDate] = value.birthDate
                stmt[ContributorTable.deathDate] = value.deathDate
                stmt[ContributorTable.website] = value.website
                stmt[ContributorTable.revision] = rev
                stmt[ContributorTable.updatedAt] = now
                stmt[ContributorTable.deletedAt] = null
                stmt[ContributorTable.clientOpId] = clientOpId
            }
        } else {
            // Must stay in sync with resolveOrCreate's lookup key: both use contributorDedupKey,
            // so a row written via a direct upsert gets the same dedup bucket that resolveOrCreate
            // would compute. value.sortName is always non-null here because resolveOrCreate stores
            // the derived sort name before calling upsert.
            val normalized = contributorDedupKey(value.name, value.sortName)
            ContributorTable.insert { stmt ->
                stmt[ContributorTable.id] = value.id
                stmt[ContributorTable.normalizedName] = normalized
                stmt[ContributorTable.name] = value.name
                stmt[ContributorTable.sortName] = value.sortName
                stmt[ContributorTable.asin] = value.asin
                stmt[ContributorTable.description] = value.description
                stmt[ContributorTable.imagePath] = value.imagePath
                stmt[ContributorTable.imageBlurHash] = value.imageBlurHash
                stmt[ContributorTable.birthDate] = value.birthDate
                stmt[ContributorTable.deathDate] = value.deathDate
                stmt[ContributorTable.website] = value.website
                stmt[ContributorTable.revision] = rev
                stmt[ContributorTable.createdAt] = now
                stmt[ContributorTable.updatedAt] = now
                stmt[ContributorTable.deletedAt] = null
                stmt[ContributorTable.clientOpId] = clientOpId
            }
        }
        ContributorAliasTable.replaceForContributor(value.id, value.aliases)
    }

    /**
     * Finds the contributor whose dedup key matches [contributorDedupKey]`(name, sortName)`,
     * or creates one through the substrate's `upsert` (which bumps the domain revision and
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
                ContributorTable
                    .selectAll()
                    .where { ContributorTable.normalizedName eq normalized }
                    .firstOrNull()
                    ?.get(ContributorTable.id)
            }
        if (existing != null) return ContributorId(existing)

        val id = ContributorId(UUID.randomUUID().toString())
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
            ContributorTable
                .selectAll()
                .where { ContributorTable.deletedAt.isNull() }
                .mapTo(HashSet()) { it[ContributorTable.id] }
        }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: ContributorId): String = idAsString(id)
}
