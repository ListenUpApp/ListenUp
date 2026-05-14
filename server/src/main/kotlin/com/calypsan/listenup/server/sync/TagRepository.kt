package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.db.TagTable
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.Database

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

    override fun ResultRow.toDto(): Tag =
        Tag(
            id = this[TagTable.id],
            name = this[TagTable.name],
            revision = this[TagTable.revision],
            updatedAt = this[TagTable.updatedAt],
            deletedAt = this[TagTable.deletedAt],
        )

    override fun Tag.writeTo(stmt: UpdateBuilder<*>) {
        stmt[TagTable.id] = id
        stmt[TagTable.name] = name
    }

    override val Tag.id: String get() = this.id

    override fun Tag.revisionOf(): Long = revision
}
