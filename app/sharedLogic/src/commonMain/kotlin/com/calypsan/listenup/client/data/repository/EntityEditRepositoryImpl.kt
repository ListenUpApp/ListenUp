package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.EntityService
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.entity.EntityChange
import com.calypsan.listenup.api.dto.entity.EntityMutation
import com.calypsan.listenup.api.dto.entity.EntityUpsert
import com.calypsan.listenup.api.dto.entity.StoryWorldOp
import com.calypsan.listenup.api.error.EntityError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.flatMap
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.result.mapSuspend
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.client.data.local.db.EntityDao
import com.calypsan.listenup.client.data.local.db.EntityEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.UnsentCancel
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.data.sync.domains.toEntityRow
import com.calypsan.listenup.client.domain.model.EntityEdit
import com.calypsan.listenup.client.domain.model.WorldEntity
import com.calypsan.listenup.client.domain.model.WorldEntityChange
import com.calypsan.listenup.client.domain.model.WorldEntityChangeOp
import com.calypsan.listenup.client.domain.model.WorldEntityDraft
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.EntityEditRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.core.currentEpochMilliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Offline-first [EntityEditRepository]. Every write reads the current row, applies the change to Room and
 * queues the full snapshot on [OutboxChannels.Entities] in one transaction ([OfflineEditor.edit]). Writes are
 * serialized by [editMutex] so two concurrent edits never queue snapshots that each miss the other's change.
 *
 * The server applies snapshots in arrival order and stamps its own clock, so an undo that is a forward
 * write wins simply by arriving after the edit it undoes. A delete is the exception — see [undo].
 *
 * [authSession] names the signed-in user, so an undo only ever reverts that user's own DELETE.
 */
internal class EntityEditRepositoryImpl(
    private val entityDao: EntityDao,
    private val offlineEditor: OfflineEditor,
    private val channel: RpcChannel<EntityService>,
    private val authSession: AuthSession,
) : EntityEditRepository {
    private val editMutex = Mutex()

    override fun observeEntitiesForSeries(seriesId: SeriesId): Flow<List<WorldEntity>> =
        entityDao.observeForSeries(seriesId.value).map { rows -> rows.map { it.toDomain() } }

    override fun observeEntitiesForBook(bookId: BookId): Flow<List<WorldEntity>> =
        entityDao.observeForBook(bookId.value).map { rows -> rows.map { it.toDomain() } }

    override fun observeEntity(id: EntityId): Flow<WorldEntity?> =
        entityDao.observeById(id.value).map { it?.toDomain() }

    override suspend fun createEntity(draft: WorldEntityDraft): AppResult<EntityEdit> =
        editMutex.withLock {
            draftProblem(draft)?.let { return@withLock AppResult.Failure(it) }
            val id = EntityId(Uuid.random().toString())
            val now = currentEpochMilliseconds()
            val row =
                EntityEntity(
                    id = id.value,
                    kind = draft.kind,
                    name = draft.name.trim(),
                    descriptor = draft.descriptor?.trim()?.ifEmpty { null },
                    parentId = draft.parentId?.value,
                    homeSeriesId = draft.homeSeriesId?.value,
                    homeBookId = draft.homeBookId?.value,
                    createdAt = now,
                    updatedAt = now,
                )
            write(row).map { EntityEdit(id, before = null) }
        }

    override suspend fun updateEntity(
        id: EntityId,
        name: String,
        descriptor: String?,
        parentId: EntityId?,
    ): AppResult<EntityEdit> =
        editMutex.withLock {
            contentProblem(name, descriptor)?.let { return@withLock AppResult.Failure(it) }
            val existing = entityDao.getById(id.value) ?: return@withLock notFound(id)
            val changed =
                existing.copy(
                    name = name.trim(),
                    descriptor = descriptor?.trim()?.ifEmpty { null },
                    parentId = parentId?.value,
                    updatedAt = currentEpochMilliseconds(),
                )
            write(changed).map { EntityEdit(id, before = existing.toDomain()) }
        }

    override suspend fun deleteEntity(id: EntityId): AppResult<EntityEdit> =
        editMutex.withLock {
            val existing = entityDao.getById(id.value) ?: return@withLock notFound(id)
            tombstone(existing).map { EntityEdit(id, before = existing.toDomain(), wasDelete = true) }
        }

    override suspend fun undo(edit: EntityEdit): AppResult<Unit> {
        val before = edit.before
        if (edit.wasDelete && before != null) return undoDelete(edit.entityId, before)
        return editMutex.withLock {
            if (before == null) {
                // Undo a create: delete what was created (nothing to do if it is already gone).
                entityDao.getById(edit.entityId.value)?.let { tombstone(it) } ?: AppResult.Success(Unit)
            } else {
                // Undo an edit: write the earlier content back over the live row, as a forward write.
                val current = entityDao.getById(edit.entityId.value) ?: return@withLock notFound(edit.entityId)
                write(current.withContentOf(before, updatedAt = currentEpochMilliseconds()))
            }
        }
    }

    /**
     * A delete is undone by what the outbox says about its Delete, never by a guess: withdrawn locally while
     * the server never accepted it (unsent, or refused), refused while its outcome is unknown, and reverted on
     * the server once sent. Only the local withdrawal holds [editMutex]; the network revert runs after it is
     * released, so a slow or unreachable server never stalls other edits.
     */
    private suspend fun undoDelete(
        id: EntityId,
        before: WorldEntity,
    ): AppResult<Unit> =
        editMutex
            .withLock {
                offlineEditor.cancelUnsent(OutboxChannels.Entities, id.value, OpKind.Delete) {
                    val tombstone = entityDao.findById(id.value)
                    if (tombstone != null) {
                        entityDao.upsert(tombstone.withContentOf(before, updatedAt = tombstone.updatedAt))
                    }
                }
            }.flatMap { outcome ->
                when (outcome) {
                    UnsentCancel.Cancelled, UnsentCancel.DeadLettered -> {
                        AppResult.Success(Unit)
                    }

                    UnsentCancel.Attempted -> {
                        AppResult.Failure(
                            TransportError.OutcomeUnknown(debugInfo = "delete of entity=${id.value}"),
                        )
                    }

                    UnsentCancel.NotQueued -> {
                        revertSentDelete(id)
                    }
                }
            }

    /**
     * Online: revert the entity's newest history entry if — and only if — it is the DELETE being undone: a
     * DELETE, made by the signed-in user. Someone else's later DELETE is never reverted through this undo.
     */
    private suspend fun revertSentDelete(id: EntityId): AppResult<Unit> {
        val history = channel.call(idempotent = true) { it.listHistory(id) }
        val newest =
            when (history) {
                is AppResult.Failure -> return history
                is AppResult.Success -> history.data.firstOrNull()
            }
        val me = authSession.getUserId()
        if (newest?.op != StoryWorldOp.DELETE || me == null || newest.actorId != me) {
            return AppResult.Failure(
                EntityError.HistoryNotFound(
                    debugInfo = "newest change of entity=${id.value} is ${newest?.op} by ${newest?.actorId}, not this user's DELETE",
                ),
            )
        }
        return channel.call { it.revert(newest.id) }.mapSuspend { change ->
            val revived = change.after ?: return@mapSuspend
            val stored = entityDao.revisionOf(revived.id)
            if (stored == null || stored <= revived.revision) entityDao.upsert(revived.toEntityRow())
        }
    }

    override suspend fun mergeEntities(
        source: EntityId,
        target: EntityId,
    ): AppResult<Unit> = channel.call { it.mergeEntities(source, target) }.map { }

    override suspend fun listHistory(id: EntityId): AppResult<List<WorldEntityChange>> =
        channel.call(idempotent = true) { it.listHistory(id) }.map { changes -> changes.map { it.toDomain() } }

    override suspend fun revert(changeId: StoryWorldHistoryId): AppResult<Unit> =
        channel
            .call {
                it.revert(changeId)
            }.map { }

    /** Optimistic Room write (live) + the queued full snapshot, in one transaction. */
    private suspend fun write(row: EntityEntity): AppResult<Unit> =
        offlineEditor.edit(OutboxChannels.Entities, row.id, EntityMutation.Upsert(row.toUpsert()), op = OpKind.Upsert) {
            entityDao.upsert(row.copy(deletedAt = null))
        }

    /** Optimistic tombstone + the queued delete. Revision kept so the server's tombstone echo still applies. */
    private suspend fun tombstone(row: EntityEntity): AppResult<Unit> {
        val now = currentEpochMilliseconds()
        return offlineEditor.edit(OutboxChannels.Entities, row.id, EntityMutation.Delete, op = OpKind.Delete) {
            entityDao.softDelete(id = row.id, deletedAt = now, revision = row.revision)
        }
    }

    private fun <T> notFound(id: EntityId): AppResult<T> =
        AppResult.Failure(EntityError.NotFound(debugInfo = "entity=${id.value}"))
}

/** The same input rules the server applies, so an offline violation reads exactly like an online one. */
private fun draftProblem(draft: WorldEntityDraft): ValidationError? =
    when {
        draft.kind == EntityKind.UNKNOWN -> {
            ValidationError(message = "Choose what kind of entry this is.", field = "kind")
        }

        listOfNotNull(draft.homeSeriesId, draft.homeBookId).size != 1 -> {
            ValidationError(message = "An entry belongs to exactly one series or book.")
        }

        else -> {
            contentProblem(draft.name, draft.descriptor)
        }
    }

private fun contentProblem(
    name: String,
    descriptor: String?,
): ValidationError? =
    when {
        name.isBlank() -> {
            ValidationError(message = "Give this entry a name.", field = "name")
        }

        name.trim().length > MAX_NAME -> {
            ValidationError(message = "Keep the name to $MAX_NAME characters.", field = "name")
        }

        (descriptor?.trim()?.length ?: 0) > MAX_DESCRIPTOR -> {
            ValidationError(message = "Keep the description to $MAX_DESCRIPTOR characters.", field = "descriptor")
        }

        else -> {
            null
        }
    }

private const val MAX_NAME = 200
private const val MAX_DESCRIPTOR = 60

/** This row with [content]'s user-editable fields, live; identity, home, authorship, image and revision kept. */
private fun EntityEntity.withContentOf(
    content: WorldEntity,
    updatedAt: Long,
): EntityEntity =
    copy(
        kind = content.kind,
        name = content.name,
        descriptor = content.descriptor,
        parentId = content.parentId?.value,
        deletedAt = null,
        updatedAt = updatedAt,
    )

private fun EntityEntity.toDomain(): WorldEntity =
    WorldEntity(
        id = EntityId(id),
        kind = kind,
        name = name,
        descriptor = descriptor,
        parentId = parentId?.let(::EntityId),
        homeSeriesId = homeSeriesId?.let(::SeriesId),
        homeBookId = homeBookId?.let(::BookId),
        createdBy = createdBy?.let(::UserId),
        updatedBy = updatedBy?.let(::UserId),
    )

private fun EntityEntity.toUpsert(): EntityUpsert =
    EntityUpsert(
        id = EntityId(id),
        kind = kind,
        name = name,
        descriptor = descriptor,
        parentId = parentId?.let(::EntityId),
        homeSeriesId = homeSeriesId?.let(::SeriesId),
        homeBookId = homeBookId?.let(::BookId),
    )

private fun EntitySyncPayload.toDomain(): WorldEntity =
    WorldEntity(
        id = EntityId(id),
        kind = kind,
        name = name,
        descriptor = descriptor,
        parentId = parentId?.let(::EntityId),
        homeSeriesId = homeSeriesId?.let(::SeriesId),
        homeBookId = homeBookId?.let(::BookId),
        createdBy = createdBy?.let(::UserId),
        updatedBy = updatedBy?.let(::UserId),
    )

private fun EntityChange.toDomain(): WorldEntityChange =
    WorldEntityChange(
        id = id,
        entityId = entityId,
        op = op.toDomain(),
        actorId = actorId?.let(::UserId),
        occurredAtMs = occurredAt,
        before = before?.toDomain(),
        after = after?.toDomain(),
    )

private fun StoryWorldOp.toDomain(): WorldEntityChangeOp =
    when (this) {
        StoryWorldOp.CREATE -> WorldEntityChangeOp.CREATE
        StoryWorldOp.UPDATE -> WorldEntityChangeOp.UPDATE
        StoryWorldOp.DELETE -> WorldEntityChangeOp.DELETE
        StoryWorldOp.MERGE -> WorldEntityChangeOp.MERGE
        StoryWorldOp.REVERT -> WorldEntityChangeOp.REVERT
    }
