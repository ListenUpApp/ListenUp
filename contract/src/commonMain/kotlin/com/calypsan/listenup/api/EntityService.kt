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
 * Permissions: creating, editing, deleting and reverting any change but a merge need
 * `canContributeStoryWorld`; merging and reverting a merge need `canCurateStoryWorld`; ROOT/ADMIN hold both. A member also needs to see the entity's home — a
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

    /** Soft-deletes [id]. Needs `canContributeStoryWorld`; history makes it one revert away. */
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
     * and returns the REVERT entry it recorded. Reverting a MERGE needs `canCurateStoryWorld`, like the
     * merge itself; reverting anything else — an UPDATE, a CREATE, a DELETE, or any change of an entity
     * that is deleted now, which the revert revives — needs `canContributeStoryWorld`.
     */
    suspend fun revert(changeId: StoryWorldHistoryId): AppResult<EntityChange>
}
