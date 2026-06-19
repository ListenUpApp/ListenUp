package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableRepository
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Syncable-domain repository for library folders (Libraries phase).
 *
 * Library folders are a global (cross-user) domain backed by [LibraryFolderTable].
 * Each folder belongs to a parent library ([LibraryFolderTable.libraryId]).
 * The substrate ([SyncableRepository]) owns revision bumping, timestamping,
 * and change-bus publication; this class supplies the row read/write shape.
 *
 * `idAsString(FolderId) = id.value` is load-bearing — the substrate's default
 * `toString()` on a value class returns `"FolderId(value=foo)"`, which would
 * corrupt every column the id is written into.
 */
class LibraryFolderRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<LibraryFolderSyncPayload, FolderId>(
        db = db,
        table = LibraryFolderTable,
        bus = bus,
        registry = registry,
        domainName = "library_folders",
        clock = clock,
    ) {
    override val elementSerializer: KSerializer<LibraryFolderSyncPayload> = LibraryFolderSyncPayload.serializer()

    override fun idAsString(id: FolderId): String = id.value

    override val LibraryFolderSyncPayload.id: FolderId
        get() = FolderId(this.id)

    override fun LibraryFolderSyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): LibraryFolderSyncPayload? =
        LibraryFolderTable
            .selectAll()
            .where { LibraryFolderTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                LibraryFolderSyncPayload(
                    id = row[LibraryFolderTable.id],
                    libraryId = row[LibraryFolderTable.libraryId],
                    rootPath = row[LibraryFolderTable.rootPath],
                    revision = row[LibraryFolderTable.revision],
                    updatedAt = row[LibraryFolderTable.updatedAt],
                    createdAt = row[LibraryFolderTable.createdAt],
                    deletedAt = row[LibraryFolderTable.deletedAt],
                )
            }

    override suspend fun writePayload(
        value: LibraryFolderSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            LibraryFolderTable.update({ LibraryFolderTable.id eq value.id }) { stmt ->
                stmt[LibraryFolderTable.libraryId] = value.libraryId
                stmt[LibraryFolderTable.rootPath] = value.rootPath
                stmt[LibraryFolderTable.revision] = rev
                stmt[LibraryFolderTable.updatedAt] = now
                stmt[LibraryFolderTable.deletedAt] = null
                stmt[LibraryFolderTable.clientOpId] = clientOpId
            }
        } else {
            LibraryFolderTable.insert { stmt ->
                stmt[LibraryFolderTable.id] = value.id
                stmt[LibraryFolderTable.libraryId] = value.libraryId
                stmt[LibraryFolderTable.rootPath] = value.rootPath
                stmt[LibraryFolderTable.revision] = rev
                stmt[LibraryFolderTable.createdAt] = now
                stmt[LibraryFolderTable.updatedAt] = now
                stmt[LibraryFolderTable.deletedAt] = null
                stmt[LibraryFolderTable.clientOpId] = clientOpId
            }
        }
    }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: FolderId): String = idAsString(id)
}
