package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.WorldEventService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.dto.worldevent.EventsBatch
import com.calypsan.listenup.api.dto.worldevent.WorldEventChange as WorldEventChangeDto
import com.calypsan.listenup.api.dto.worldevent.WorldEventOp
import com.calypsan.listenup.api.dto.worldevent.WorldEventUpsert
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.error.WorldEventError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.result.mapSuspend
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.client.data.local.db.EntityDao
import com.calypsan.listenup.client.data.local.db.WorldEventDao
import com.calypsan.listenup.client.data.local.db.WorldEventEntity
import com.calypsan.listenup.client.data.local.db.WorldEventWithMentions
import com.calypsan.listenup.client.data.local.db.replaceMentions
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.UnsentCancel
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.data.sync.domains.toWorldEventRow
import com.calypsan.listenup.client.domain.model.WorldEvent
import com.calypsan.listenup.client.domain.model.WorldEventAnchor
import com.calypsan.listenup.client.domain.model.WorldEventChange
import com.calypsan.listenup.client.domain.model.WorldEventChangeOp
import com.calypsan.listenup.client.domain.model.WorldEventContent
import com.calypsan.listenup.client.domain.model.WorldEventDraft
import com.calypsan.listenup.client.domain.model.WorldEventEdit
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.WorldEventEditRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.core.WorldEventId
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.domain.storyworld.MentionTokens
import com.calypsan.listenup.domain.storyworld.WorldEventRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Offline-first [WorldEventEditRepository]. Every write checks the server's rules, then applies the change to
 * Room and queues one [EventsBatch] on [OutboxChannels.WorldEvents] in one transaction ([OfflineEditor.edit]).
 * A single write is a batch of one, keyed by its event id with the op it carries; [recordEvents] is one batch
 * keyed by its first id. Writes are serialized by [editMutex] so two concurrent edits never queue snapshots
 * that each miss the other's change.
 *
 * The server applies batches in arrival order and stamps its own clock, so an undo that is a forward write
 * wins by arriving after the edit it undoes. A delete is the exception — see [undo].
 */
internal class WorldEventEditRepositoryImpl(
    private val worldEventDao: WorldEventDao,
    private val entityDao: EntityDao,
    private val offlineEditor: OfflineEditor,
    private val channel: RpcChannel<WorldEventService>,
    private val authSession: AuthSession,
) : WorldEventEditRepository {
    private val editMutex = Mutex()

    override fun observeEventsForSeries(seriesId: SeriesId): Flow<List<WorldEvent>> =
        worldEventDao.observeForSeries(seriesId.value).map { rows -> rows.map { it.toDomain() } }

    override fun observeEventsForBook(bookId: BookId): Flow<List<WorldEvent>> =
        worldEventDao.observeForBook(bookId.value).map { rows -> rows.map { it.toDomain() } }

    override fun observeEventsAnchoredTo(bookId: BookId): Flow<List<WorldEvent>> =
        worldEventDao.observeAnchoredTo(bookId.value).map { rows -> rows.map { it.toDomain() } }

    override fun observeEventsMentioning(entityId: EntityId): Flow<List<WorldEvent>> =
        worldEventDao.observeMentioning(entityId.value).map { rows -> rows.map { it.toDomain() } }

    override fun observeEvent(id: WorldEventId): Flow<WorldEvent?> =
        worldEventDao.observeById(id.value).map {
            it?.toDomain()
        }

    override suspend fun recordEvent(draft: WorldEventDraft): AppResult<WorldEventEdit> =
        recordEvents(listOf(draft)).map {
            it.single()
        }

    override suspend fun recordEvents(drafts: List<WorldEventDraft>): AppResult<List<WorldEventEdit>> =
        editMutex.withLock {
            if (drafts.isEmpty()) return@withLock AppResult.Success(emptyList())
            if (drafts.size > WorldEventRules.MAX_BATCH) {
                return@withLock AppResult.Failure(
                    ValidationError(message = "Send at most ${WorldEventRules.MAX_BATCH} changes at once."),
                )
            }
            val upserts =
                drafts.map { draft ->
                    upsertOf(
                        id = WorldEventId(Uuid.random().toString()),
                        content = draft.content,
                        homeSeriesId = draft.homeSeriesId,
                        homeBookId = draft.homeBookId,
                    )
                }
            for (upsert in upserts) creationProblem(upsert)?.let { return@withLock AppResult.Failure(it) }
            val now = currentEpochMilliseconds()
            val rows = upserts.map { it.toRow(existing = null, now = now) }
            offlineEditor
                .edit(
                    channel = OutboxChannels.WorldEvents,
                    entityId = rows.first().id,
                    patch = EventsBatch(upserts.map { WorldEventOp.Upsert(it) }),
                    op = OpKind.Upsert,
                ) {
                    rows.forEach { applyRow(it) }
                }.map { rows.map { row -> WorldEventEdit(WorldEventId(row.id), before = null) } }
        }

    override suspend fun updateEvent(
        id: WorldEventId,
        content: WorldEventContent,
    ): AppResult<WorldEventEdit> =
        editMutex.withLock {
            val existing = worldEventDao.getById(id.value) ?: return@withLock notFound(id)
            val upsert =
                upsertOf(
                    id = id,
                    content = content,
                    homeSeriesId = existing.homeSeriesId?.let(::SeriesId),
                    homeBookId = existing.homeBookId?.let(::BookId),
                )
            val type = if (upsert.type == WorldEventType.UNKNOWN) existing.type else upsert.type
            val stillNamed =
                setOfNotNull(
                    existing.subjectEntityId,
                    existing.objectEntityId,
                ).mapTo(HashSet(), ::EntityId)
            contentProblem(upsert, type, mayBeDeleted = stillNamed)?.let { return@withLock AppResult.Failure(it) }
            val before = existing.toDomain(worldEventDao.mentionIdsFor(id.value))
            write(upsert, existing).map { WorldEventEdit(id, before = before) }
        }

    override suspend fun deleteEvent(id: WorldEventId): AppResult<WorldEventEdit> =
        editMutex.withLock {
            val existing = worldEventDao.getById(id.value) ?: return@withLock notFound(id)
            val before = existing.toDomain(worldEventDao.mentionIdsFor(id.value))
            tombstone(existing).map { WorldEventEdit(id, before = before, wasDelete = true) }
        }

    override suspend fun undo(edit: WorldEventEdit): AppResult<Unit> {
        val before = edit.before
        if (edit.wasDelete && before != null) return undoDelete(edit.eventId, before)
        return editMutex.withLock {
            if (before == null) {
                // Undo a create: delete what was created (nothing to do if it is already gone).
                worldEventDao.getById(edit.eventId.value)?.let { tombstone(it) } ?: AppResult.Success(Unit)
            } else {
                // Undo an edit: write the earlier content back over the live row, as a forward write.
                val current = worldEventDao.getById(edit.eventId.value) ?: return@withLock notFound(edit.eventId)
                write(before.asUpsert(), current)
            }
        }
    }

    /**
     * A delete is undone by what the outbox says about its Delete, never by a guess. Only the local withdrawal
     * holds [editMutex]; the network revert runs after it is released.
     */
    private suspend fun undoDelete(
        id: WorldEventId,
        before: WorldEvent,
    ): AppResult<Unit> =
        editMutex
            .withLock {
                offlineEditor.cancelUnsent(OutboxChannels.WorldEvents, id.value, OpKind.Delete) {
                    val tombstone = worldEventDao.findById(id.value)
                    if (tombstone != null) {
                        val restored =
                            before.asUpsert().toRow(existing = tombstone, now = tombstone.updatedAt)
                        applyRow(restored)
                    }
                }
            }.flatMap { outcome ->
                when (outcome) {
                    UnsentCancel.Cancelled, UnsentCancel.DeadLettered -> {
                        AppResult.Success(Unit)
                    }

                    UnsentCancel.Attempted -> {
                        AppResult.Failure(
                            TransportError.OutcomeUnknown(debugInfo = "delete of event=${id.value}"),
                        )
                    }

                    UnsentCancel.NotQueued -> {
                        revertSentDelete(id)
                    }
                }
            }

    /** Online: revert the event's newest history entry iff it is the signed-in user's DELETE. */
    private suspend fun revertSentDelete(id: WorldEventId): AppResult<Unit> {
        val history = channel.call(idempotent = true) { it.listHistory(id) }
        val newest =
            when (history) {
                is AppResult.Failure -> return history
                is AppResult.Success -> history.data.firstOrNull()
            }
        val me = authSession.getUserId()
        if (newest?.op != StoryWorldOp.DELETE || me == null || newest.actorId != me) {
            return AppResult.Failure(
                WorldEventError.HistoryNotFound(
                    debugInfo =
                        "newest change of event=${id.value} is ${newest?.op ?: "absent"} by " +
                            "${newest?.actorId ?: "nobody"}, not this user's DELETE",
                ),
            )
        }
        return channel.call { it.revert(newest.id) }.mapSuspend { change ->
            val revived = change.after ?: return@mapSuspend
            val stored = worldEventDao.revisionOf(revived.id)
            if (stored == null || stored <= revived.revision) {
                worldEventDao.upsert(revived.toWorldEventRow())
                worldEventDao.replaceMentions(revived.id, revived.mentionIds)
            }
        }
    }

    override suspend fun listHistory(id: WorldEventId): AppResult<List<WorldEventChange>> =
        channel.call(idempotent = true) { it.listHistory(id) }.map { changes -> changes.map { it.toDomain() } }

    override suspend fun revert(changeId: StoryWorldHistoryId): AppResult<Unit> =
        channel
            .call {
                it.revert(changeId)
            }.map { }

    /** The server's create rules, applied offline: home, type, content, then kinds and worlds against Room. */
    private suspend fun creationProblem(upsert: WorldEventUpsert): AppError? =
        WorldEventRules.homeProblem(upsert.homeSeriesId, upsert.homeBookId)
            ?: WorldEventRules.creationProblem(upsert.type)
            ?: contentProblem(upsert, upsert.type, mayBeDeleted = emptySet())

    /** [mayBeDeleted]: the participants the stored event already names, which may have been deleted since. */
    private suspend fun contentProblem(
        upsert: WorldEventUpsert,
        type: WorldEventType,
        mayBeDeleted: Set<EntityId>,
    ): AppError? =
        WorldEventRules.contentProblem(upsert, type)
            ?: anchorProblem(upsert)
            ?: participantProblem(upsert, type, mayBeDeleted)

    /** A book-homed event can be pinned only to its own book; series membership is the server's to check. */
    private fun anchorProblem(upsert: WorldEventUpsert): AppError? {
        val anchor = upsert.bookId ?: return null
        val home = upsert.homeBookId ?: return null
        return if (anchor == home) null else WorldEventError.InvalidAnchor(debugInfo = "book=${anchor.value}")
    }

    /**
     * The server's participant rule: each is an entity of the event's world, and live unless [mayBeDeleted] — an
     * edit that keeps a deleted character is allowed, so deleting one never freezes the events naming it.
     */
    private suspend fun participantProblem(
        upsert: WorldEventUpsert,
        type: WorldEventType,
        mayBeDeleted: Set<EntityId>,
    ): AppError? {
        val subjectKind = upsert.subjectEntityId?.let { kindInWorld(it, upsert, mayBeDeleted) ?: return notInWorld(it) }
        val objectKind = upsert.objectEntityId?.let { kindInWorld(it, upsert, mayBeDeleted) ?: return notInWorld(it) }
        return WorldEventRules.kindProblem(type, subjectKind, objectKind)
    }

    /** The kind of [id] if it is an entity of [upsert]'s world — live, or deleted and in [mayBeDeleted] — else null. */
    private suspend fun kindInWorld(
        id: EntityId,
        upsert: WorldEventUpsert,
        mayBeDeleted: Set<EntityId>,
    ): EntityKind? =
        (if (id in mayBeDeleted) entityDao.findById(id.value) else entityDao.getById(id.value))
            ?.takeIf { it.homeSeriesId == upsert.homeSeriesId?.value && it.homeBookId == upsert.homeBookId?.value }
            ?.kind

    private fun notInWorld(id: EntityId) = WorldEventError.EntityNotInWorld(debugInfo = "entity=${id.value}")

    /** Optimistic Room write (live) + the queued batch of one, in one transaction. */
    private suspend fun write(
        upsert: WorldEventUpsert,
        existing: WorldEventEntity,
    ): AppResult<Unit> {
        val row = upsert.toRow(existing = existing, now = currentEpochMilliseconds())
        return offlineEditor.edit(
            channel = OutboxChannels.WorldEvents,
            entityId = row.id,
            patch = EventsBatch(listOf(WorldEventOp.Upsert(upsert))),
            op = OpKind.Upsert,
        ) {
            applyRow(row)
        }
    }

    /** Optimistic tombstone + the queued delete. Revision kept so the server's tombstone echo still applies. */
    private suspend fun tombstone(row: WorldEventEntity): AppResult<Unit> {
        val now = currentEpochMilliseconds()
        return offlineEditor.edit(
            channel = OutboxChannels.WorldEvents,
            entityId = row.id,
            patch = EventsBatch(listOf(WorldEventOp.Delete(WorldEventId(row.id)))),
            op = OpKind.Delete,
        ) {
            worldEventDao.softDelete(id = row.id, deletedAt = now, revision = row.revision)
        }
    }

    /** The row, live, with this client's guess at its mentions until the server's set arrives. */
    private suspend fun applyRow(row: WorldEventEntity) {
        worldEventDao.upsert(row.copy(deletedAt = null))
        worldEventDao.replaceMentions(row.id, localMentionIds(row))
    }

    private fun <T> notFound(id: WorldEventId): AppResult<T> =
        AppResult.Failure(WorldEventError.NotFound(debugInfo = "event=${id.value}"))
}

/** The snapshot sent for [content] at this home: text and detail trimmed, a blank detail dropped. */
private fun upsertOf(
    id: WorldEventId,
    content: WorldEventContent,
    homeSeriesId: SeriesId?,
    homeBookId: BookId?,
): WorldEventUpsert =
    WorldEventUpsert(
        id = id,
        type = content.type,
        text = content.text.trim(),
        detail = content.detail?.run { trim().ifEmpty { null } },
        homeSeriesId = homeSeriesId,
        homeBookId = homeBookId,
        bookId = content.anchor?.bookId,
        positionMs = content.anchor?.positionMs,
        subjectEntityId = content.subjectId,
        objectEntityId = content.objectId,
    )

/** This event's content as the snapshot that writes it back at its own home. */
private fun WorldEvent.asUpsert(): WorldEventUpsert =
    upsertOf(id = id, content = content, homeSeriesId = homeSeriesId, homeBookId = homeBookId)

/** [existing] (or a fresh row) carrying this upsert's content, live; an UNKNOWN type keeps the stored one. */
private fun WorldEventUpsert.toRow(
    existing: WorldEventEntity?,
    now: Long,
): WorldEventEntity {
    val base = existing ?: WorldEventEntity(id = id.value, type = type, createdAt = now, updatedAt = now)
    return base.copy(
        homeSeriesId = homeSeriesId?.value,
        homeBookId = homeBookId?.value,
        bookId = bookId?.value,
        positionMs = positionMs,
        type = if (type == WorldEventType.UNKNOWN) base.type else type,
        text = text,
        detail = detail,
        subjectEntityId = subjectEntityId?.value,
        objectEntityId = objectEntityId?.value,
        deletedAt = null,
        updatedAt = now,
    )
}

/** The ids this client believes [row] mentions; the server's set replaces it on the echo. */
private fun localMentionIds(row: WorldEventEntity): List<String> =
    (MentionTokens.extractMentionIds(row.text) + setOfNotNull(row.subjectEntityId, row.objectEntityId))
        .filter { it.isNotBlank() }

private fun WorldEventWithMentions.toDomain(): WorldEvent = event.toDomain(mentionIds)

private fun WorldEventEntity.toDomain(mentionIds: Collection<String>): WorldEvent =
    WorldEvent(
        id = WorldEventId(id),
        homeSeriesId = homeSeriesId?.let(::SeriesId),
        homeBookId = homeBookId?.let(::BookId),
        content =
            WorldEventContent(
                type = type,
                text = text,
                detail = detail,
                anchor = anchorOf(bookId, positionMs),
                subjectId = subjectEntityId?.let(::EntityId),
                objectId = objectEntityId?.let(::EntityId),
            ),
        mentionIds = mentionIds.filter { it.isNotBlank() }.mapTo(LinkedHashSet(), ::EntityId),
        createdBy = createdBy?.let(::UserId),
        updatedBy = updatedBy?.let(::UserId),
    )

private fun WorldEventSyncPayload.toDomain(): WorldEvent = toWorldEventRow().toDomain(mentionIds)

private fun anchorOf(
    bookId: String?,
    positionMs: Long?,
): WorldEventAnchor? = if (bookId != null && positionMs != null) WorldEventAnchor(BookId(bookId), positionMs) else null

private fun WorldEventChangeDto.toDomain(): WorldEventChange =
    WorldEventChange(
        id = id,
        eventId = eventId,
        op = op.toEventChangeOp(),
        actorId = actorId?.let(::UserId),
        occurredAtMs = occurredAt,
        before = before?.toDomain(),
        after = after?.toDomain(),
    )

private fun StoryWorldOp.toEventChangeOp(): WorldEventChangeOp =
    when (this) {
        StoryWorldOp.CREATE -> WorldEventChangeOp.CREATE

        StoryWorldOp.UPDATE -> WorldEventChangeOp.UPDATE

        StoryWorldOp.DELETE -> WorldEventChangeOp.DELETE

        StoryWorldOp.REVERT -> WorldEventChangeOp.REVERT

        // Never recorded for an event: events are not merged. Read as an update rather than fail a history page.
        StoryWorldOp.MERGE -> WorldEventChangeOp.UPDATE
    }
