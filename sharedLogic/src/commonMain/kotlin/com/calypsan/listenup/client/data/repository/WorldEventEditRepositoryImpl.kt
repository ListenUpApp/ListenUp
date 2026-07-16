package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.world.EventsBatch
import com.calypsan.listenup.api.dto.world.WorldEventOp
import com.calypsan.listenup.api.dto.world.WorldEventUpsert
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.api.sync.WorldEventSource
import com.calypsan.listenup.client.data.local.db.WorldEventDao
import com.calypsan.listenup.client.data.local.db.WorldEventEntity
import com.calypsan.listenup.client.data.local.db.WorldEventWithMentions
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.data.sync.domains.worldEventMentionIds
import com.calypsan.listenup.client.domain.model.NewWorldEvent
import com.calypsan.listenup.client.domain.model.WorldEvent
import com.calypsan.listenup.client.domain.repository.WorldEventEditRepository
import com.calypsan.listenup.core.currentEpochMilliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Offline-first Story World event-log editor.
 *
 * Every write builds one [EventsBatch] — a single write is a batch of one op — applies the
 * optimistic Room merge (event row + replaced mention rows), and enqueues the batch as ONE
 * durable outbox row, all in one transaction via [OfflineEditor.edit]. There is no direct
 * [com.calypsan.listenup.client.data.remote.RpcChannel] dependency here — unlike some domains'
 * online-only RPC calls, every world-event write is offline-first; the outbox sender
 * ([com.calypsan.listenup.client.di.clientSyncModule]) dispatches the whole batch through
 * [com.calypsan.listenup.api.WorldEventService.applyBatch] in one round-trip.
 *
 * **[recordBatch]'s outbox key.** A multi-op batch has no single natural aggregate id — each op
 * targets an independent, freshly-minted event. The outbox row is keyed on the FIRST minted
 * event's id: the anti-flicker shield ([com.calypsan.listenup.client.data.sync.domains.OutboxInFlightQuery])
 * only needs to cover one id to prevent a stale echo from clobbering the batch while it's in
 * flight, since the whole batch sends and confirms atomically — the other events in the batch
 * are brand-new client-minted ids no other actor can reference until the batch has been sent.
 *
 * **Writes are serialized through [editMutex].** [update]'s read → build-snapshot → edit sequence
 * is a read-modify-write over the WHOLE aggregate: two concurrent edits to the same event that
 * both read the pre-edit snapshot would each queue a payload missing the other's change, and the
 * second op's server apply (then its ServerWins echo) would silently erase the first — both
 * having returned Success. One coarse repository-level mutex closes that window — the
 * [com.calypsan.listenup.client.data.repository.EntityEditRepositoryImpl] precedent. Coarse
 * rather than per-event-id: event-log editing is low-throughput human composing, so cross-event
 * contention is negligible and the single lock avoids per-id map bookkeeping. Every write op
 * takes it — including [record]/[recordBatch] (fresh state) and [delete] (no aggregate read),
 * which don't strictly need it — because "all event writes are serialized" is a simpler
 * invariant to hold than a per-method exception list.
 */
internal class WorldEventEditRepositoryImpl(
    private val worldEventDao: WorldEventDao,
    private val offlineEditor: OfflineEditor,
) : WorldEventEditRepository {
    /** Serializes every write's read → build-snapshot → edit sequence; see the class KDoc. */
    private val editMutex = Mutex()

    override fun observeForBook(bookId: String): Flow<List<WorldEvent>> =
        worldEventDao.observeForBook(bookId).map { events -> events.map { it.toDomain() } }

    override fun observeForEntity(entityId: String): Flow<List<WorldEvent>> =
        worldEventDao.observeForEntity(entityId).map { events -> events.map { it.toDomain() } }

    override fun observeForWorld(
        homeSeriesId: String?,
        homeBookId: String?,
    ): Flow<List<WorldEvent>> =
        worldEventDao.observeForWorld(homeSeriesId, homeBookId).map { events -> events.map { it.toDomain() } }

    override fun observeEvent(id: String): Flow<WorldEvent?> =
        worldEventDao.observeById(id).map { event -> event?.toDomain() }

    override suspend fun record(event: NewWorldEvent): AppResult<String> =
        recordBatch(listOf(event)).map { it.single() }

    override suspend fun recordBatch(events: List<NewWorldEvent>): AppResult<List<String>> =
        editMutex.withLock {
            if (events.isEmpty()) return@withLock AppResult.Success(emptyList())
            for (event in events) {
                validateHome(event.homeSeriesId, event.homeBookId)
                    ?.let { return@withLock AppResult.Failure(it) }
            }
            val now = currentEpochMilliseconds()
            val minted = events.map { it to Uuid.random().toString() }
            val batch = EventsBatch(minted.map { (event, id) -> WorldEventOp.Upsert(event.toUpsert(id)) })
            offlineEditor
                .edit(OutboxChannels.WorldEvents, minted.first().second, batch) {
                    minted.forEach { (event, id) -> applyNewEventLocally(id, event, now) }
                }.map { minted.map { it.second } }
        }

    override suspend fun update(upsert: WorldEventUpsert): AppResult<Unit> =
        editMutex.withLock {
            validateHome(upsert.homeSeriesId, upsert.homeBookId)?.let { return@withLock AppResult.Failure(it) }
            val existing =
                worldEventDao.getById(upsert.id) ?: return@withLock AppResult.Failure(eventNotFound(upsert.id))
            val batch = EventsBatch(listOf(WorldEventOp.Upsert(upsert)))
            offlineEditor.edit(OutboxChannels.WorldEvents, upsert.id, batch) {
                worldEventDao.upsert(
                    existing.copy(
                        homeSeriesId = upsert.homeSeriesId,
                        homeBookId = upsert.homeBookId,
                        bookId = upsert.bookId,
                        positionMs = upsert.positionMs,
                        type = upsert.type,
                        text = upsert.text,
                        subjectEntityId = upsert.subjectEntityId,
                        objectEntityId = upsert.objectEntityId,
                        // revision/deletedAt/createdAt/updatedAt/source/trackId/trackVersion
                        // deliberately untouched — carried forward from `existing`.
                    ),
                )
                worldEventDao.replaceMentions(
                    upsert.id,
                    worldEventMentionIds(upsert.text, upsert.subjectEntityId, upsert.objectEntityId),
                )
            }
        }

    override suspend fun delete(id: String): AppResult<Unit> =
        editMutex.withLock {
            val now = currentEpochMilliseconds()
            val batch = EventsBatch(listOf(WorldEventOp.Delete(id)))
            offlineEditor.edit(OutboxChannels.WorldEvents, id, batch) {
                // Revision preserved (not bumped) so the server's authoritative tombstone echo
                // still re-applies through the revision guard on drain — the Entity/Series pattern.
                worldEventDao.getById(id)?.let { existing ->
                    worldEventDao.softDelete(id = id, deletedAt = now, revision = existing.revision)
                    worldEventDao.deleteMentionsForEvent(id)
                }
            }
        }

    private suspend fun applyNewEventLocally(
        id: String,
        event: NewWorldEvent,
        now: Long,
    ) {
        worldEventDao.upsert(
            WorldEventEntity(
                id = id,
                homeSeriesId = event.homeSeriesId,
                homeBookId = event.homeBookId,
                bookId = event.bookId,
                positionMs = event.positionMs,
                type = event.type,
                text = event.text,
                subjectEntityId = event.subjectEntityId,
                objectEntityId = event.objectEntityId,
                source = WorldEventSource.MANUAL,
                trackId = null,
                trackVersion = null,
                // A brand-new local-only row: revision = 0 marks it as a not-yet-synced stub, the
                // same convention EntityEditRepositoryImpl.createEntity uses — any real server
                // revision (>= 0) supersedes it on the confirming echo.
                revision = 0,
                deletedAt = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        worldEventDao.replaceMentions(
            id,
            worldEventMentionIds(event.text, event.subjectEntityId, event.objectEntityId),
        )
    }
}

private fun NewWorldEvent.toUpsert(id: String): WorldEventUpsert =
    WorldEventUpsert(
        id = id,
        homeSeriesId = homeSeriesId,
        homeBookId = homeBookId,
        bookId = bookId,
        positionMs = positionMs,
        type = type,
        text = text,
        subjectEntityId = subjectEntityId,
        objectEntityId = objectEntityId,
    )

/**
 * Events are dual-homed: exactly one of [homeSeriesId] / [homeBookId] must be non-null — see
 * [com.calypsan.listenup.api.sync.WorldEventSyncPayload] for the dual-home rule. Returns null when
 * that rule holds, a [ValidationError] otherwise. Mirrors the server-side
 * `WorldEventServiceImpl` shape guard word-for-word (the [EntityEditRepositoryImpl]
 * `validateHome` precedent), so the same violation reads identically whether it's caught locally
 * (offline) or echoed back from the server.
 */
private fun validateHome(
    homeSeriesId: String?,
    homeBookId: String?,
): ValidationError? {
    val seriesHomeMissing = homeSeriesId == null
    val bookHomeMissing = homeBookId == null
    return if (seriesHomeMissing == bookHomeMissing) {
        ValidationError(message = "Exactly one of homeSeriesId or homeBookId must be set.")
    } else {
        null
    }
}

/** Client-local not-found failure for a world-event op whose target row is absent from Room. */
private fun eventNotFound(id: String): SyncError.NotFound =
    SyncError.NotFound(domain = SyncDomains.WORLD_EVENTS.name, entityId = id)

private fun WorldEventWithMentions.toDomain(): WorldEvent =
    WorldEvent(
        id = event.id,
        homeSeriesId = event.homeSeriesId,
        homeBookId = event.homeBookId,
        bookId = event.bookId,
        positionMs = event.positionMs,
        type = event.type,
        text = event.text,
        subjectEntityId = event.subjectEntityId,
        objectEntityId = event.objectEntityId,
        mentionIds = mentionIds,
        source = event.source,
    )
