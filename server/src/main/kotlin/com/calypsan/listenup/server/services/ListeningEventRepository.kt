package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.core.ListeningEventId
import com.calypsan.listenup.server.db.ListeningEventTable
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
 * Syncable-domain repository for per-user listening events (Playback P2).
 *
 * One row per closed playback span — an uninterrupted period of audio playback
 * with a single `playbackSpeed`. The domain is append-only: [writePayload]
 * inserts on first write and advances only `revision`/`updatedAt`/`clientOpId`
 * on a re-upsert of the same id (idempotent pending-op replay).
 *
 * `userScoped = true` — every `upsert`, `softDelete`, `pullSince`, and `digest`
 * call routes through the per-user dimension of the substrate.
 *
 * `idAsString(ListeningEventId) = id.value` is load-bearing — Kotlin's default
 * `toString()` on a value class returns `"ListeningEventId(value=foo)"`, which
 * would corrupt every column the id is written to.
 */
class ListeningEventRepository(
    db: Database,
    bus: ChangeBus,
    registry: SyncRegistry,
    clock: Clock = Clock.System,
) : SyncableRepository<ListeningEventSyncPayload, ListeningEventId>(
        db = db,
        table = ListeningEventTable,
        bus = bus,
        registry = registry,
        domainName = "listening_events",
        clock = clock,
    ) {
    override val userScoped: Boolean = true

    override val elementSerializer: KSerializer<ListeningEventSyncPayload> =
        ListeningEventSyncPayload.serializer()

    override fun idAsString(id: ListeningEventId): String = id.value

    override val ListeningEventSyncPayload.id: ListeningEventId
        get() = ListeningEventId(this.id)

    override fun ListeningEventSyncPayload.revisionOf(): Long = revision

    override suspend fun readPayload(idStr: String): ListeningEventSyncPayload? =
        ListeningEventTable
            .selectAll()
            .where { ListeningEventTable.id eq idStr }
            .firstOrNull()
            ?.let { row ->
                ListeningEventSyncPayload(
                    id = row[ListeningEventTable.id],
                    bookId = row[ListeningEventTable.bookId],
                    startPositionMs = row[ListeningEventTable.startPositionMs],
                    endPositionMs = row[ListeningEventTable.endPositionMs],
                    startedAt = row[ListeningEventTable.startedAt],
                    endedAt = row[ListeningEventTable.endedAt],
                    playbackSpeed = row[ListeningEventTable.playbackSpeed],
                    tz = row[ListeningEventTable.tz],
                    deviceLabel = row[ListeningEventTable.deviceLabel],
                    revision = row[ListeningEventTable.revision],
                    updatedAt = row[ListeningEventTable.updatedAt],
                    createdAt = row[ListeningEventTable.createdAt],
                    deletedAt = row[ListeningEventTable.deletedAt],
                )
            }

    override suspend fun writePayload(
        value: ListeningEventSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        userId: String?,
        existed: Boolean,
    ) {
        requireNotNull(userId) { "ListeningEventRepository.writePayload requires a userId" }
        if (existed) {
            // Append-only domain — a duplicate upsert of the same id advances
            // revision/updatedAt/clientOpId so the pending-op queue's idempotent
            // re-fire round-trips correctly, but domain fields are never mutated.
            ListeningEventTable.update({ ListeningEventTable.id eq value.id }) { stmt ->
                stmt[ListeningEventTable.revision] = rev
                stmt[ListeningEventTable.updatedAt] = now
                stmt[ListeningEventTable.clientOpId] = clientOpId
            }
        } else {
            ListeningEventTable.insert { stmt ->
                stmt[ListeningEventTable.id] = value.id
                stmt[ListeningEventTable.userId] = userId
                stmt[ListeningEventTable.bookId] = value.bookId
                stmt[ListeningEventTable.startPositionMs] = value.startPositionMs
                stmt[ListeningEventTable.endPositionMs] = value.endPositionMs
                stmt[ListeningEventTable.startedAt] = value.startedAt
                stmt[ListeningEventTable.endedAt] = value.endedAt
                stmt[ListeningEventTable.playbackSpeed] = value.playbackSpeed
                stmt[ListeningEventTable.tz] = value.tz
                stmt[ListeningEventTable.deviceLabel] = value.deviceLabel
                stmt[ListeningEventTable.revision] = rev
                stmt[ListeningEventTable.createdAt] = now
                stmt[ListeningEventTable.updatedAt] = now
                stmt[ListeningEventTable.deletedAt] = null
                stmt[ListeningEventTable.clientOpId] = clientOpId
            }
        }
    }

    /** Test-only accessor for the protected [idAsString]. */
    internal fun idAsStringForTest(id: ListeningEventId): String = idAsString(id)
}
