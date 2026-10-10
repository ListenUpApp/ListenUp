package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.worldevent.EventsBatch
import com.calypsan.listenup.api.dto.worldevent.WorldEventChange
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.core.WorldEventId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC surface for Story World events — the lines of a world's log.
 *
 * Permissions: every write and every revert needs `canContributeStoryWorld` (ROOT/ADMIN hold it). Events are
 * never merged, so curate plays no part. A member also needs to see the event's home and, for an anchored
 * event, its anchor book: an event out of sight answers
 * [com.calypsan.listenup.api.error.WorldEventError.NotFound] for writes and history and is left out of
 * listings. Every write records a [WorldEventChange] in the same transaction.
 */
@Rpc
interface WorldEventService {
    /**
     * Applies every op of [batch] in one transaction: either all land or none does. Conflicts resolve in
     * arrival order, like every synced write; the history entry each op records makes an overwritten state
     * one revert away. An empty batch, or one over the batch limit, is a
     * [com.calypsan.listenup.api.error.ValidationError].
     */
    suspend fun applyBatch(batch: EventsBatch): AppResult<Unit>

    /** The live events homed on [seriesId] that the caller can see, oldest first. */
    suspend fun listEventsForSeries(seriesId: SeriesId): AppResult<List<WorldEventSyncPayload>>

    /** The live events homed on [bookId], if the caller can see the book, oldest first. */
    suspend fun listEventsForBook(bookId: BookId): AppResult<List<WorldEventSyncPayload>>

    /** The live events that mention [entityId] — as subject, object or a text token — that the caller can see. */
    suspend fun listEventsForEntity(entityId: EntityId): AppResult<List<WorldEventSyncPayload>>

    /** [eventId]'s history, newest first. Anyone who can see the event may read it. */
    suspend fun listHistory(eventId: WorldEventId): AppResult<List<WorldEventChange>>

    /**
     * Restores the `before` of [changeId] as a new forward write (a CREATE reverts to a delete) and returns the
     * REVERT entry it recorded. Needs `canContributeStoryWorld`.
     */
    suspend fun revert(changeId: StoryWorldHistoryId): AppResult<WorldEventChange>
}
