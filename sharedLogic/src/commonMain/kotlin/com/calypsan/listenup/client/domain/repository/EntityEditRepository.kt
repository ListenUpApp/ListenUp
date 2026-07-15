@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.client.domain.model.BioEntry
import com.calypsan.listenup.client.domain.model.Entity
import kotlinx.coroutines.flow.Flow

/**
 * Client-side read + write surface for Story World entity (character/location/item) editing
 * (Story World Stage 2).
 *
 * **Read model:** entities are mirrored into Room by the sync engine and observed reactively
 * ([observeEntitiesForSeries], [observeEntity]) — Room is the single source of truth, matching
 * every other curated-content domain ([SeriesEditRepository], [ContributorEditRepository]).
 *
 * **Write model:** every write is **offline-first** — an optimistic Room write plus a durable
 * outbox op in one transaction; the SSE echo confirms/reconciles. Unlike series/genres/tags,
 * [createEntity] is ALSO offline-first: the id is client-minted (a random UUID), so there is no
 * server-minted-id RPC to wait for. Bio-entry edits ([upsertBioEntry], [removeBioEntry]) each
 * replay as a full-aggregate [com.calypsan.listenup.api.dto.entity.EntityMutation.Upsert] — the
 * backing [com.calypsan.listenup.api.EntityService.upsertEntity] replaces the entity's whole
 * bio-entry set on every write, there is no incremental per-entry RPC.
 *
 * Part of the domain layer — the implementation lives in the data layer.
 */
interface EntityEditRepository {
    /**
     * Observe every non-tombstoned entity namespaced under [seriesId], ordered by name.
     *
     * [Entity.bioEntries] is left empty in this list projection — see [Entity]'s KDoc.
     *
     * @param seriesId The series whose entities are observed.
     */
    fun observeEntitiesForSeries(seriesId: String): Flow<List<Entity>>

    /**
     * Observe a single entity by id, including its full bio-entry set. Emits null when absent
     * or tombstoned.
     *
     * @param id The entity to observe.
     */
    fun observeEntity(id: String): Flow<Entity?>

    /**
     * Create a new entity — **offline-first**: the id is client-minted, so the optimistic Room
     * write and the durable outbox op both apply immediately with no server round-trip.
     *
     * @param kind The entity's taxonomy — character, location, or item.
     * @param name The entity's first-introduced (pre-reveal) name.
     * @param homeSeriesId The series to namespace the entity under.
     * @return [AppResult.Success] with the newly-minted entity id, or [AppResult.Failure] on a local error.
     */
    suspend fun createEntity(
        kind: EntityKind,
        name: String,
        homeSeriesId: String,
    ): AppResult<String>

    /**
     * Update an existing entity's name/image — **offline-first**: optimistic Room write plus
     * durable outbox op. The entity's kind, home series, and bio entries carry forward
     * unchanged (this is a whole-aggregate write; see the class KDoc).
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
     * Add or replace a bio entry on [entityId] — **offline-first**. A blank
     * [BioEntry.id][com.calypsan.listenup.client.domain.model.BioEntry.id] mints a new entry;
     * a non-blank id replaces the existing entry sharing that id. Every other live entry on the
     * entity carries forward unchanged.
     *
     * @param entityId The entity to add/replace the bio entry on.
     * @param entry The bio entry to upsert.
     */
    suspend fun upsertBioEntry(
        entityId: String,
        entry: BioEntry,
    ): AppResult<Unit>

    /**
     * Remove a bio entry from [entityId] — **offline-first**. Every other live entry on the
     * entity carries forward unchanged.
     *
     * @param entityId The entity to remove the bio entry from.
     * @param entryId The bio entry to remove.
     */
    suspend fun removeBioEntry(
        entityId: String,
        entryId: String,
    ): AppResult<Unit>

    /**
     * Soft-delete an entity — **offline-first**: optimistic Room tombstone plus durable outbox
     * op.
     *
     * @param id The entity to delete.
     */
    suspend fun deleteEntity(id: String): AppResult<Unit>
}
