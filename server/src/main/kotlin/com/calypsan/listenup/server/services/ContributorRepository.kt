package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.db.ContributorAliasTable
import com.calypsan.listenup.server.db.ContributorTable
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
        val normalized = normalizeForDedup(value.name)
        if (existed) {
            ContributorTable.update({ ContributorTable.id eq value.id }) { stmt ->
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
                stmt[ContributorTable.updatedAt] = now
                stmt[ContributorTable.deletedAt] = null
                stmt[ContributorTable.clientOpId] = clientOpId
            }
        } else {
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
     * Finds the contributor whose name shares [name]'s normalized form, or
     * creates one through the substrate's `upsert` (which bumps the domain
     * revision and publishes `SyncEvent.Created`). Returns the stable
     * [ContributorId] either way.
     *
     * Idempotent: a rescan of unchanged books re-resolves existing contributors
     * with no event and no revision bump. The display name preserves the first
     * writer's casing.
     *
     * The find-miss → create window is a benign race only under SQLite's
     * single-writer model; the single-threaded scan never triggers it.
     */
    suspend fun resolveOrCreate(name: String): ContributorId {
        val normalized = normalizeForDedup(name)
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
