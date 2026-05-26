package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.db.TagTable
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
 * Syncable repository for tags.
 *
 * Handles the full tag aggregate: read/write of [Tag] via [TagTable], including
 * [Tag.slug] which is the canonical URL-safe identity for each tag. Slug is
 * written on insert and included in every payload read.
 *
 * Service-layer helpers beyond the base substrate:
 *  - [findById] — fetch one non-deleted tag by id
 *  - [findBySlug] — fetch one non-deleted tag by slug (the stable URL identity)
 *  - [listAll] — fetch all non-deleted tags, ordered by name
 *  - [updateName] — rename a tag (updates [Tag.name] only; slug is preserved)
 */
class TagRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<Tag, String>(db, TagTable, bus, registry, "tags", clock) {
    override val elementSerializer: KSerializer<Tag> = Tag.serializer()

    override val Tag.id: String get() = this.id

    override fun Tag.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): Tag? =
        TagTable
            .selectAll()
            .where { TagTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                Tag(
                    id = row[TagTable.id],
                    name = row[TagTable.name],
                    slug = row[TagTable.slug],
                    revision = row[TagTable.revision],
                    updatedAt = row[TagTable.updatedAt],
                    deletedAt = row[TagTable.deletedAt],
                )
            }

    override suspend fun writePayload(
        value: Tag,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            TagTable.update({ TagTable.id eq value.id }) { stmt ->
                stmt[TagTable.name] = value.name
                stmt[TagTable.slug] = value.slug
                stmt[TagTable.revision] = rev
                stmt[TagTable.updatedAt] = now
                stmt[TagTable.deletedAt] = null
                stmt[TagTable.clientOpId] = clientOpId
            }
        } else {
            TagTable.insert { stmt ->
                stmt[TagTable.id] = value.id
                stmt[TagTable.name] = value.name
                stmt[TagTable.slug] = value.slug
                stmt[TagTable.revision] = rev
                stmt[TagTable.createdAt] = now
                stmt[TagTable.updatedAt] = now
                stmt[TagTable.deletedAt] = null
                stmt[TagTable.clientOpId] = clientOpId
            }
        }
    }

    /**
     * Returns the non-deleted tag with [id], or null when absent or tombstoned.
     */
    suspend fun findById(id: String): Tag? =
        suspendTransaction(db) {
            TagTable
                .selectAll()
                .where { (TagTable.id eq id) and TagTable.deletedAt.isNull() }
                .firstOrNull()
                ?.let { row ->
                    Tag(
                        id = row[TagTable.id],
                        name = row[TagTable.name],
                        slug = row[TagTable.slug],
                        revision = row[TagTable.revision],
                        updatedAt = row[TagTable.updatedAt],
                        deletedAt = row[TagTable.deletedAt],
                    )
                }
        }

    /**
     * Returns the non-deleted tag whose [Tag.slug] matches [slug], or null when
     * absent or tombstoned. Slugs are normalized to lowercase at creation time;
     * this lookup is inherently case-insensitive.
     */
    suspend fun findBySlug(slug: String): Tag? =
        suspendTransaction(db) {
            TagTable
                .selectAll()
                .where { (TagTable.slug eq slug) and TagTable.deletedAt.isNull() }
                .firstOrNull()
                ?.let { row ->
                    Tag(
                        id = row[TagTable.id],
                        name = row[TagTable.name],
                        slug = row[TagTable.slug],
                        revision = row[TagTable.revision],
                        updatedAt = row[TagTable.updatedAt],
                        deletedAt = row[TagTable.deletedAt],
                    )
                }
        }

    /**
     * Returns all non-deleted tags ordered by name ascending.
     */
    suspend fun listAll(): List<Tag> =
        suspendTransaction(db) {
            TagTable
                .selectAll()
                .where { TagTable.deletedAt.isNull() }
                .orderBy(TagTable.name)
                .map { row ->
                    Tag(
                        id = row[TagTable.id],
                        name = row[TagTable.name],
                        slug = row[TagTable.slug],
                        revision = row[TagTable.revision],
                        updatedAt = row[TagTable.updatedAt],
                        deletedAt = row[TagTable.deletedAt],
                    )
                }
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
            is com.calypsan.listenup.api.result.AppResult.Success -> result.data
            is com.calypsan.listenup.api.result.AppResult.Failure -> null
        }
    }
}
