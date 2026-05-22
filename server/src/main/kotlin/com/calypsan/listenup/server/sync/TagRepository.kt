package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.db.TagTable
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Validation-domain repository. Tags has no public write API in this phase;
 * tests exercise the substrate directly through this class.
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
                stmt[TagTable.revision] = rev
                stmt[TagTable.updatedAt] = now
                stmt[TagTable.deletedAt] = null
                stmt[TagTable.clientOpId] = clientOpId
            }
        } else {
            TagTable.insert { stmt ->
                stmt[TagTable.id] = value.id
                stmt[TagTable.name] = value.name
                stmt[TagTable.revision] = rev
                stmt[TagTable.createdAt] = now
                stmt[TagTable.updatedAt] = now
                stmt[TagTable.deletedAt] = null
                stmt[TagTable.clientOpId] = clientOpId
            }
        }
    }
}
