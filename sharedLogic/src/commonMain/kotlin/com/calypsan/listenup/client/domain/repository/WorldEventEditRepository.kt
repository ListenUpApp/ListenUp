@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.dto.world.WorldEventUpsert
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.NewWorldEvent
import com.calypsan.listenup.client.domain.model.WorldEvent
import kotlinx.coroutines.flow.Flow

/**
 * Client-side read + write surface for the Story World unified event log.
 *
 * **Read model:** events are mirrored into Room by the sync engine and observed reactively
 * ([observeForBook], [observeForEntity], [observeForWorld], [observeEvent]) — Room is the single
 * source of truth, matching every other curated-content domain ([EntityEditRepository]).
 *
 * **Write model:** every write is **offline-first** — an optimistic Room write plus a durable
 * outbox op in one transaction; the SSE echo confirms/reconciles. [record] and [recordBatch] are
 * ALSO offline-first for the same reason [EntityEditRepository.createEntity] is: the id is
 * client-minted, so there is no server-minted-id RPC to wait for.
 *
 * Part of the domain layer — the implementation lives in the data layer.
 */
interface WorldEventEditRepository {
    /** Observe every non-tombstoned event anchored to [bookId], ordered by its book position. */
    fun observeForBook(bookId: String): Flow<List<WorldEvent>>

    /**
     * Observe every non-tombstoned event that mentions [entityId] — as its subject, its object,
     * or an inline `@entity` token in its text.
     */
    fun observeForEntity(entityId: String): Flow<List<WorldEvent>>

    /**
     * Observe every non-tombstoned event namespaced under exactly one of [homeSeriesId] /
     * [homeBookId].
     *
     * @param homeSeriesId The series whose events are observed, or null when observing by book.
     * @param homeBookId The standalone book whose events are observed, or null when observing by series.
     */
    fun observeForWorld(
        homeSeriesId: String? = null,
        homeBookId: String? = null,
    ): Flow<List<WorldEvent>>

    /** Observe a single event by id. Emits null when absent or tombstoned. */
    fun observeEvent(id: String): Flow<WorldEvent?>

    /**
     * Record a brand-new event — **offline-first**: the id is client-minted, so the optimistic
     * Room write and the durable outbox op both apply immediately with no server round-trip.
     * Equivalent to `recordBatch(listOf(event)).map { it.single() }`.
     *
     * Exactly one of [NewWorldEvent.homeSeriesId] / [NewWorldEvent.homeBookId] must be non-null —
     * see [com.calypsan.listenup.api.sync.WorldEventSyncPayload] for the dual-home rule. A call
     * that violates it fails with the same
     * [com.calypsan.listenup.api.error.ValidationError] the server returns for the equivalent
     * RPC-level violation, with no Room write and no queued op.
     *
     * @param event The new event's fields.
     * @return [AppResult.Success] with the newly-minted event id, or [AppResult.Failure] on a local error.
     */
    suspend fun record(event: NewWorldEvent): AppResult<String>

    /**
     * Record a batch of brand-new events atomically — **offline-first**, ids client-minted. Every
     * event in [events] is applied to Room and queued as ONE outbox row (a single
     * [com.calypsan.listenup.api.dto.world.EventsBatch] carrying every op), matching the
     * composer's "one turn, several typed events" use case — e.g. a scene entry that both moves a
     * character and records an item transfer.
     *
     * @param events The new events' fields, in the order they should be applied.
     * @return [AppResult.Success] with the newly-minted event ids, in [events] order, or
     *   [AppResult.Failure] on a local error (no event is applied).
     */
    suspend fun recordBatch(events: List<NewWorldEvent>): AppResult<List<String>>

    /**
     * Update an existing event to [upsert]'s full snapshot — **offline-first**: optimistic Room
     * write plus durable outbox op. [upsert] must carry the event's COMPLETE current state (this
     * is a whole-aggregate write, mirroring [com.calypsan.listenup.api.dto.world.WorldEventUpsert]'s
     * own contract); fields the caller omits are not carried forward from Room automatically.
     *
     * @param upsert The event's complete new state, keyed by its existing id.
     */
    suspend fun update(upsert: WorldEventUpsert): AppResult<Unit>

    /**
     * Soft-delete an event — **offline-first**: optimistic Room tombstone plus durable outbox op.
     *
     * @param id The event to delete.
     */
    suspend fun delete(id: String): AppResult<Unit>
}
