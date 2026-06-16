package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.server.db.MoodTable
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Syncable repository for moods.
 *
 * Handles the full mood aggregate: read/write of [Mood] via [MoodTable], including
 * [Mood.slug] which is the canonical URL-safe identity for each mood. Slug is
 * written on insert and included in every payload read.
 *
 * Service-layer helpers beyond the base substrate:
 *  - [findById] — fetch one non-deleted mood by id
 *  - [findBySlug] — fetch one non-deleted mood by slug (the stable URL identity)
 *  - [listAll] — fetch all non-deleted moods, ordered by name
 *  - [updateName] — rename a mood (updates [Mood.name] only; slug is preserved)
 */
class MoodRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<Mood, String>(db, MoodTable, bus, registry, "moods", clock) {
    override val elementSerializer: KSerializer<Mood> = Mood.serializer()

    override val Mood.id: String get() = this.id

    override fun Mood.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): Mood? =
        MoodTable
            .selectAll()
            .where { MoodTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                Mood(
                    id = row[MoodTable.id],
                    name = row[MoodTable.name],
                    slug = row[MoodTable.slug],
                    revision = row[MoodTable.revision],
                    updatedAt = row[MoodTable.updatedAt],
                    deletedAt = row[MoodTable.deletedAt],
                )
            }

    override suspend fun writePayload(
        value: Mood,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            MoodTable.update({ MoodTable.id eq value.id }) { stmt ->
                stmt[MoodTable.name] = value.name
                stmt[MoodTable.slug] = value.slug
                stmt[MoodTable.revision] = rev
                stmt[MoodTable.updatedAt] = now
                stmt[MoodTable.deletedAt] = null
                stmt[MoodTable.clientOpId] = clientOpId
            }
        } else {
            MoodTable.insert { stmt ->
                stmt[MoodTable.id] = value.id
                stmt[MoodTable.name] = value.name
                stmt[MoodTable.slug] = value.slug
                stmt[MoodTable.revision] = rev
                stmt[MoodTable.createdAt] = now
                stmt[MoodTable.updatedAt] = now
                stmt[MoodTable.deletedAt] = null
                stmt[MoodTable.clientOpId] = clientOpId
            }
        }
    }

    /**
     * Returns the non-deleted mood with [id], or null when absent or tombstoned.
     */
    suspend fun findById(id: String): Mood? =
        suspendTransaction(db) {
            MoodTable
                .selectAll()
                .where { (MoodTable.id eq id) and MoodTable.deletedAt.isNull() }
                .firstOrNull()
                ?.let { row ->
                    Mood(
                        id = row[MoodTable.id],
                        name = row[MoodTable.name],
                        slug = row[MoodTable.slug],
                        revision = row[MoodTable.revision],
                        updatedAt = row[MoodTable.updatedAt],
                        deletedAt = row[MoodTable.deletedAt],
                    )
                }
        }

    /**
     * Returns the non-deleted mood whose [Mood.slug] matches [slug], or null when
     * absent or tombstoned. Slugs are normalized to lowercase at creation time;
     * this lookup is inherently case-insensitive.
     */
    suspend fun findBySlug(slug: String): Mood? =
        suspendTransaction(db) {
            MoodTable
                .selectAll()
                .where { (MoodTable.slug eq slug) and MoodTable.deletedAt.isNull() }
                .firstOrNull()
                ?.let { row ->
                    Mood(
                        id = row[MoodTable.id],
                        name = row[MoodTable.name],
                        slug = row[MoodTable.slug],
                        revision = row[MoodTable.revision],
                        updatedAt = row[MoodTable.updatedAt],
                        deletedAt = row[MoodTable.deletedAt],
                    )
                }
        }

    /**
     * Returns all non-deleted moods ordered by name ascending.
     */
    suspend fun listAll(): List<Mood> =
        suspendTransaction(db) {
            MoodTable
                .selectAll()
                .where { MoodTable.deletedAt.isNull() }
                .orderBy(MoodTable.name)
                .map { row ->
                    Mood(
                        id = row[MoodTable.id],
                        name = row[MoodTable.name],
                        slug = row[MoodTable.slug],
                        revision = row[MoodTable.revision],
                        updatedAt = row[MoodTable.updatedAt],
                        deletedAt = row[MoodTable.deletedAt],
                    )
                }
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
}
