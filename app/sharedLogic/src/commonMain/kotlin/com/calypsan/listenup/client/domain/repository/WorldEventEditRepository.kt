@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.WorldEvent
import com.calypsan.listenup.client.domain.model.WorldEventChange
import com.calypsan.listenup.client.domain.model.WorldEventContent
import com.calypsan.listenup.client.domain.model.WorldEventDraft
import com.calypsan.listenup.client.domain.model.WorldEventEdit
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.core.WorldEventId
import kotlinx.coroutines.flow.Flow

/**
 * Story World events for the UI. Reads come from Room. Record, edit and delete are offline-first: an
 * optimistic Room write plus one queued `world_events` batch, in one transaction. History and [revert] of an
 * older change need the server; [undo] needs it only for a delete that has already been sent.
 *
 * The same rules as the server apply before anything is queued (the shared `WorldEventRules`, plus kind and
 * same-world checks against the mirrored entities), so an offline refusal reads exactly like an online one.
 * No contract DTO crosses this surface.
 */
interface WorldEventEditRepository {
    /** Live events homed on [seriesId], oldest first. */
    fun observeEventsForSeries(seriesId: SeriesId): Flow<List<WorldEvent>>

    /** Live events homed on [bookId], oldest first. */
    fun observeEventsForBook(bookId: BookId): Flow<List<WorldEvent>>

    /** Live events anchored to a moment in [bookId], in book order. */
    fun observeEventsAnchoredTo(bookId: BookId): Flow<List<WorldEvent>>

    /** Live events that mention [entityId] — as subject, object or a text token — oldest first. */
    fun observeEventsMentioning(entityId: EntityId): Flow<List<WorldEvent>>

    /** One event; null when absent or deleted. */
    fun observeEvent(id: WorldEventId): Flow<WorldEvent?>

    /** Records one event with a client-minted id. Offline-first. */
    suspend fun recordEvent(draft: WorldEventDraft): AppResult<WorldEventEdit>

    /**
     * Records several events as one atomic batch — all land or none does — with client-minted ids, in [drafts]
     * order. Offline-first. Each returned edit undoes on its own.
     */
    suspend fun recordEvents(drafts: List<WorldEventDraft>): AppResult<List<WorldEventEdit>>

    /** Replaces [id]'s content; its home never changes. Offline-first. */
    suspend fun updateEvent(
        id: WorldEventId,
        content: WorldEventContent,
    ): AppResult<WorldEventEdit>

    /** Deletes [id]. Offline-first. */
    suspend fun deleteEvent(id: WorldEventId): AppResult<WorldEventEdit>

    /**
     * Reverses the just-made [edit], exactly as `EntityEditRepository.undo` does:
     * - **an edit** writes [WorldEventEdit.before] back as a forward write through the outbox. Works offline.
     * - **a create** deletes what was created, through the outbox. Works offline.
     * - **a delete** depends on whether its Delete has been sent, read from the outbox under its drain lock:
     *   - unsent, or parked by a failure that never reached the server, or refused (a dead letter): withdrawn and
     *     the row restored in one transaction, with no server call. Works offline.
     *   - sent: online, the event's newest history entry must be that DELETE, made by the signed-in user, and
     *     reverting it revives the event. Otherwise it is
     *     [com.calypsan.listenup.api.error.WorldEventError.HistoryNotFound]. Offline, the typed transport error.
     *   - possibly landed without a verdict: [com.calypsan.listenup.api.error.TransportError.OutcomeUnknown];
     *     nothing changes.
     */
    suspend fun undo(edit: WorldEventEdit): AppResult<Unit>

    /** [id]'s history, newest first. Needs the server. */
    suspend fun listHistory(id: WorldEventId): AppResult<List<WorldEventChange>>

    /** Reverts an older change. Needs the server; the result arrives as a sync frame. */
    suspend fun revert(changeId: StoryWorldHistoryId): AppResult<Unit>
}
