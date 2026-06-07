package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.error.LibraryError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.LibrarySyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.SyncableRepository
import com.calypsan.listenup.server.sync.nextRevision
import kotlin.time.Clock
import kotlinx.serialization.KSerializer
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Syncable-domain repository for libraries (Libraries phase).
 *
 * Libraries are a global (cross-user) domain backed by [LibraryTable].
 * The substrate ([SyncableRepository]) owns revision bumping, timestamping,
 * and change-bus publication; this class supplies the row read/write shape.
 *
 * `idAsString(LibraryId) = id.value` is load-bearing — the substrate's default
 * `toString()` on a value class returns `"LibraryId(value=foo)"`, which would
 * corrupt every column the id is written into.
 */
class LibraryRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<LibrarySyncPayload, LibraryId>(
        db = db,
        table = LibraryTable,
        bus = bus,
        registry = registry,
        domainName = "libraries",
        clock = clock,
    ) {
    override val elementSerializer: KSerializer<LibrarySyncPayload> = LibrarySyncPayload.serializer()

    override fun idAsString(id: LibraryId): String = id.value

    override val LibrarySyncPayload.id: LibraryId
        get() = LibraryId(this.id)

    override fun LibrarySyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): LibrarySyncPayload? =
        LibraryTable
            .selectAll()
            .where { LibraryTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                LibrarySyncPayload(
                    id = row[LibraryTable.id],
                    name = row[LibraryTable.name],
                    metadataPrecedence = row[LibraryTable.metadataPrecedence],
                    accessMode = row[LibraryTable.accessMode],
                    createdByUserId = row[LibraryTable.createdByUserId],
                    revision = row[LibraryTable.revision],
                    updatedAt = row[LibraryTable.updatedAt],
                    createdAt = row[LibraryTable.createdAt],
                    deletedAt = row[LibraryTable.deletedAt],
                )
            }

    override suspend fun writePayload(
        value: LibrarySyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        if (existed) {
            LibraryTable.update({ LibraryTable.id eq value.id }) { stmt ->
                stmt[LibraryTable.name] = value.name
                stmt[LibraryTable.metadataPrecedence] = value.metadataPrecedence
                stmt[LibraryTable.accessMode] = value.accessMode
                stmt[LibraryTable.createdByUserId] = value.createdByUserId
                stmt[LibraryTable.revision] = rev
                stmt[LibraryTable.updatedAt] = now
                stmt[LibraryTable.deletedAt] = null
                stmt[LibraryTable.clientOpId] = clientOpId
            }
        } else {
            LibraryTable.insert { stmt ->
                stmt[LibraryTable.id] = value.id
                stmt[LibraryTable.name] = value.name
                stmt[LibraryTable.metadataPrecedence] = value.metadataPrecedence
                stmt[LibraryTable.accessMode] = value.accessMode
                stmt[LibraryTable.createdByUserId] = value.createdByUserId
                stmt[LibraryTable.revision] = rev
                stmt[LibraryTable.createdAt] = now
                stmt[LibraryTable.updatedAt] = now
                stmt[LibraryTable.deletedAt] = null
                stmt[LibraryTable.clientOpId] = clientOpId
            }
        }
    }

    /**
     * Reads the `inbox_enabled` gate for [libraryId], or `false` when no live row
     * exists. The flag lives only on [LibraryTable] — it is deliberately absent
     * from [LibrarySyncPayload] (a server-side scanner gate, not member-synced) —
     * so the admin-facing read path resolves it directly here.
     */
    suspend fun readInboxEnabled(libraryId: LibraryId): Boolean =
        suspendTransaction(db) {
            LibraryTable
                .selectAll()
                .where { LibraryTable.id eq libraryId.value }
                .firstOrNull()
                ?.let { it[LibraryTable.deletedAt] == null && it[LibraryTable.inboxEnabled] }
                ?: false
        }

    /**
     * Sets the `inbox_enabled` gate for [libraryId] to [enabled], bumping the
     * library's revision and publishing a [SyncEvent.Updated] so connected clients
     * reconcile reactively. The flag itself is not carried on [LibrarySyncPayload]
     * (server-side scanner gate, not member-synced), so this writes the column
     * directly rather than routing through [upsert].
     *
     * Returns [LibraryError.NotFound] when no live row exists for [libraryId].
     */
    suspend fun setInboxEnabled(
        libraryId: LibraryId,
        enabled: Boolean,
    ): AppResult<Unit> =
        suspendTransaction(db) {
            val idStr = libraryId.value
            val isLive =
                LibraryTable
                    .selectAll()
                    .where { LibraryTable.id eq idStr }
                    .firstOrNull()
                    ?.let { it[LibraryTable.deletedAt] == null } == true
            if (!isLive) {
                return@suspendTransaction AppResult.Failure(LibraryError.NotFound())
            }

            val rev = nextRevision()
            val now = clock.now().toEpochMilliseconds()
            LibraryTable.update({ LibraryTable.id eq idStr }) { stmt ->
                stmt[LibraryTable.inboxEnabled] = enabled
                stmt[LibraryTable.revision] = rev
                stmt[LibraryTable.updatedAt] = now
                stmt[LibraryTable.clientOpId] = null
            }

            val saved = readPayload(idStr) ?: error("library $idStr vanished mid-transaction")
            bus.publish(
                repo = this@LibraryRepository,
                event =
                    SyncEvent.Updated(
                        id = idStr,
                        revision = rev,
                        occurredAt = now,
                        clientOpId = null,
                        payload = saved,
                    ),
                userId = null,
            )
            AppResult.Success(Unit)
        }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: LibraryId): String = idAsString(id)
}
