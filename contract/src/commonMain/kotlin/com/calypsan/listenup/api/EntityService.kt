package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.entity.EntityUpsert
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntitySyncPayload
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for Story World entity (character/location/item) curation.
 *
 * Entities are library-shared world data, namespaced under a series — unlike
 * [ReadingOrderService]'s user-owned reading orders, there is no per-caller
 * ownership: any authenticated caller may read, and any caller holding the
 * metadata-edit permission ([com.calypsan.listenup.api.error.AuthError.PermissionDenied]
 * otherwise) may write, the same curation model
 * [SeriesService]/[GenreService]/[TagService] use for their content.
 *
 * [upsertEntity] is both create and update: a new [EntityUpsert.id] creates, an
 * existing one updates. Both are last-write-wins by
 * [EntitySyncPayload.updatedAt][com.calypsan.listenup.api.sync.EntitySyncPayload.updatedAt] —
 * see the server-side `EntityRepository` for the staleness guard — so replaying a
 * queued offline write is always safe to retry.
 */
@Rpc
interface EntityService {
    /**
     * Creates or updates the entity identified by [EntityUpsert.id], and returns the
     * persisted [EntitySyncPayload].
     *
     * Gated on the metadata-edit permission
     * ([com.calypsan.listenup.api.error.AuthError.PermissionDenied] when denied). The write is
     * idempotent: replaying the identical upsert never regresses the stored content — see the
     * class KDoc's last-write-wins note.
     *
     * @param upsert The full-field entity snapshot to persist.
     */
    suspend fun upsertEntity(upsert: EntityUpsert): AppResult<EntitySyncPayload>

    /**
     * Soft-deletes the entity identified by [id].
     *
     * Gated on the metadata-edit permission
     * ([com.calypsan.listenup.api.error.AuthError.PermissionDenied] when denied). Fails with
     * [com.calypsan.listenup.api.error.SyncError.NotFound] when no entity with [id] exists.
     *
     * @param id Identifies the entity to delete.
     */
    suspend fun deleteEntity(id: String): AppResult<Unit>

    /**
     * Returns every live (non-tombstoned) entity namespaced under [seriesId].
     *
     * Open to any authenticated caller — entities are library-shared, not access-gated
     * per-book the way [BookService] content is.
     *
     * @param seriesId Identifies the series whose entities are requested.
     */
    suspend fun listEntitiesForSeries(seriesId: String): AppResult<List<EntitySyncPayload>>
}
