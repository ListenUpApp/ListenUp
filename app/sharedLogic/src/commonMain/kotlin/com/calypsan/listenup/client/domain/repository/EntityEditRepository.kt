@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.EntityEdit
import com.calypsan.listenup.client.domain.model.WorldEntity
import com.calypsan.listenup.client.domain.model.WorldEntityChange
import com.calypsan.listenup.client.domain.model.WorldEntityDraft
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import kotlinx.coroutines.flow.Flow

/**
 * Story World entities for the UI. Reads come from Room. Create, edit and delete are offline-first — an
 * optimistic Room write plus a queued `entities` op in one transaction. Merge, history and [revert] of an
 * older change need the server; [undo] needs it only for a delete that has already been sent.
 *
 * No contract DTO crosses this surface: history ops are the domain's [com.calypsan.listenup.client.domain.model.WorldEntityChangeOp].
 * The shared value types every client already speaks — the `core` ids, `EntityKind`, and `UserId` (as on
 * [com.calypsan.listenup.client.domain.model.User]) — appear as they are.
 */
interface EntityEditRepository {
    /** Live entities homed on [seriesId], by name. */
    fun observeEntitiesForSeries(seriesId: SeriesId): Flow<List<WorldEntity>>

    /** Live entities homed on [bookId], by name. */
    fun observeEntitiesForBook(bookId: BookId): Flow<List<WorldEntity>>

    /** One entity; null when absent or deleted. */
    fun observeEntity(id: EntityId): Flow<WorldEntity?>

    /** Creates an entity with a client-minted id. Offline-first. */
    suspend fun createEntity(draft: WorldEntityDraft): AppResult<EntityEdit>

    /** Renames, re-describes or re-parents [id]. Offline-first. */
    suspend fun updateEntity(
        id: EntityId,
        name: String,
        descriptor: String?,
        parentId: EntityId?,
    ): AppResult<EntityEdit>

    /** Deletes [id]. Offline-first. */
    suspend fun deleteEntity(id: EntityId): AppResult<EntityEdit>

    /**
     * Reverses the just-made [edit]:
     * - **an edit** writes [EntityEdit.before] back as a new forward write through the outbox, which wins
     *   because it reaches the server later. Works offline.
     * - **a create** deletes what was created, queuing a Delete through the outbox. Works offline.
     * - **a delete** depends on whether the Delete has been sent — read from the outbox under its drain
     *   lock, never guessed. The server never lets an upsert revive a tombstone, so a forward write can't
     *   undo it:
     *   - still queued and never sent: the Delete is withdrawn and the row restored in one transaction;
     *     nothing reaches the server. Works offline.
     *   - parked by a failure that never reached the server (unreachable, timeout, auth) counts as unsent.
     *   - refused by the server (a dead letter): the server never accepted it, so local truth wins — the dead
     *     letter is withdrawn and the row restored, offline, with no server call.
     *   - already sent: online, the entity's newest history entry must be that DELETE, made by the signed-in
     *     user, and reverting it revives the entity; the revived row is written to Room. If anything has
     *     happened to the entity since, or the newest DELETE is someone else's, it is
     *     [com.calypsan.listenup.api.error.EntityError.HistoryNotFound] — history can still revert it
     *     deliberately. Offline, the failure is the typed transport error and the row stays deleted
     *     locally — no optimistic revival the server would contradict.
     *   - possibly landed without a verdict (a lost response, a server error, a send that threw): it may
     *     have landed, so [com.calypsan.listenup.api.error.TransportError.OutcomeUnknown] and nothing
     *     changes; the Delete stays queued.
     *
     * The network revert runs outside the lock that serializes edits, so a slow server never stalls other
     * edits behind an undo.
     */
    suspend fun undo(edit: EntityEdit): AppResult<Unit>

    /** Merges [source] into [target]. Needs the server; the result arrives as a sync frame. */
    suspend fun mergeEntities(
        source: EntityId,
        target: EntityId,
    ): AppResult<Unit>

    /** [id]'s history, newest first. Needs the server. */
    suspend fun listHistory(id: EntityId): AppResult<List<WorldEntityChange>>

    /** Reverts an older change. Needs the server; the result arrives as a sync frame. */
    suspend fun revert(changeId: StoryWorldHistoryId): AppResult<Unit>
}
