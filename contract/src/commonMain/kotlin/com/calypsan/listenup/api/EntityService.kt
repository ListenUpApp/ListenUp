package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.entity.EntityChange
import com.calypsan.listenup.api.dto.entity.EntityUpsert
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC surface for Story World entities.
 *
 * Permissions: creating, editing and reverting an edit need `canContributeStoryWorld`; merging,
 * deleting and reverting anything structural need `canCurateStoryWorld`; ROOT/ADMIN hold both. A member also needs to see the entity's home — a
 * hidden home answers [com.calypsan.listenup.api.error.EntityError.NotFound] for writes and an empty
 * list for reads. Every write records an [EntityChange] in the same transaction.
 */
@Rpc
interface EntityService {
    /**
     * Creates or edits the entity [EntityUpsert.id] names. Conflicts resolve in arrival order, like
     * every synced write: the snapshot that reaches the server last overwrites, and the history entry
     * it records makes the overwritten state one revert away.
     */
    suspend fun upsertEntity(upsert: EntityUpsert): AppResult<EntitySyncPayload>

    /** Soft-deletes [id]. Curator only. */
    suspend fun deleteEntity(id: EntityId): AppResult<Unit>

    /**
     * Folds [source] into [target] (same kind, same home): the source's children move to the target and
     * the source is tombstoned. Curator only. Returns the target.
     */
    suspend fun mergeEntities(
        source: EntityId,
        target: EntityId,
    ): AppResult<EntitySyncPayload>

    /** The live entities homed on [seriesId], if the caller can see the series. */
    suspend fun listEntitiesForSeries(seriesId: SeriesId): AppResult<List<EntitySyncPayload>>

    /** The live entities homed on [bookId], if the caller can see the book. */
    suspend fun listEntitiesForBook(bookId: BookId): AppResult<List<EntitySyncPayload>>

    /** [entityId]'s edit history, newest first. Anyone who can see the entity may read it. */
    suspend fun listHistory(entityId: EntityId): AppResult<List<EntityChange>>

    /**
     * Restores the `before` state of [changeId] as a new forward write (a CREATE reverts to a delete)
     * and returns the REVERT entry it recorded. Reverting a content edit (an UPDATE: a rename, a descriptor
     * or parent change) needs `canContributeStoryWorld`; reverting a CREATE, DELETE or MERGE — or any
     * change of an entity that is deleted now, which the revert would revive — needs `canCurateStoryWorld`,
     * like the structural action itself.
     */
    suspend fun revert(changeId: StoryWorldHistoryId): AppResult<EntityChange>
}
