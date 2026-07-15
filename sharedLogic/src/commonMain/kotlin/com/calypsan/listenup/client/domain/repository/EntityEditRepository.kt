@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.client.domain.model.Entity
import kotlinx.coroutines.flow.Flow

/**
 * Client-side read + write surface for Story World entity (character/location/item) editing
 * (Story World Stage 2).
 *
 * **Read model:** entities are mirrored into Room by the sync engine and observed reactively
 * ([observeEntitiesForSeries], [observeEntitiesForBook], [observeEntity]) — Room is the single
 * source of truth, matching every other curated-content domain ([SeriesEditRepository],
 * [ContributorEditRepository]).
 *
 * **Write model:** every write is **offline-first** — an optimistic Room write plus a durable
 * outbox op in one transaction; the SSE echo confirms/reconciles. Unlike series/genres/tags,
 * [createEntity] is ALSO offline-first: the id is client-minted (a random UUID), so there is no
 * server-minted-id RPC to wait for.
 *
 * Part of the domain layer — the implementation lives in the data layer.
 */
interface EntityEditRepository {
    /**
     * Observe every non-tombstoned entity namespaced under [seriesId], ordered by name.
     *
     * @param seriesId The series whose entities are observed.
     */
    fun observeEntitiesForSeries(seriesId: String): Flow<List<Entity>>

    /**
     * Observe every non-tombstoned entity namespaced under standalone [bookId], ordered by name.
     *
     * @param bookId The standalone book whose entities are observed.
     */
    fun observeEntitiesForBook(bookId: String): Flow<List<Entity>>

    /**
     * Observe a single entity by id. Emits null when absent or tombstoned.
     *
     * @param id The entity to observe.
     */
    fun observeEntity(id: String): Flow<Entity?>

    /**
     * Create a new entity — **offline-first**: the id is client-minted, so the optimistic Room
     * write and the durable outbox op both apply immediately with no server round-trip.
     *
     * Exactly one of [homeSeriesId] / [homeBookId] must be non-null — see
     * [com.calypsan.listenup.api.sync.EntitySyncPayload] for the dual-home rule. A call that
     * violates it fails with the same
     * [com.calypsan.listenup.api.error.ValidationError] the server returns for the equivalent
     * RPC-level violation, with no Room write and no queued op.
     *
     * @param kind The entity's taxonomy — character, location, or item.
     * @param name The entity's first-introduced (pre-reveal) name.
     * @param homeSeriesId The series to namespace the entity under. Exactly one of
     *   [homeSeriesId] / [homeBookId] must be non-null.
     * @param homeBookId The standalone book to namespace the entity under. Exactly one of
     *   [homeSeriesId] / [homeBookId] must be non-null.
     * @return [AppResult.Success] with the newly-minted entity id, or [AppResult.Failure] on a local error.
     */
    suspend fun createEntity(
        kind: EntityKind,
        name: String,
        homeSeriesId: String? = null,
        homeBookId: String? = null,
    ): AppResult<String>

    /**
     * Update an existing entity's name/image — **offline-first**: optimistic Room write plus
     * durable outbox op. The entity's kind and home (series or book) carry forward unchanged
     * (this is a whole-aggregate write; see the class KDoc).
     *
     * @param id The entity to update.
     * @param name The new name.
     * @param imageRef The new portrait/image reference (null to clear).
     */
    suspend fun updateCore(
        id: String,
        name: String,
        imageRef: String?,
    ): AppResult<Unit>

    /**
     * Soft-delete an entity — **offline-first**: optimistic Room tombstone plus durable outbox
     * op.
     *
     * @param id The entity to delete.
     */
    suspend fun deleteEntity(id: String): AppResult<Unit>
}
