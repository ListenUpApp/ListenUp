package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.entity.EntityMutation
import com.calypsan.listenup.api.dto.entity.EntityUpsert
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.EntityDao
import com.calypsan.listenup.client.data.local.db.EntityEntity
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.Entity
import com.calypsan.listenup.client.domain.repository.EntityEditRepository
import com.calypsan.listenup.core.currentEpochMilliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Offline-first Story World entity editor.
 *
 * Every write reads the entity's CURRENT Room state, builds the full-aggregate [EntityUpsert]
 * snapshot, then applies the optimistic Room merge and enqueues the durable
 * [EntityMutation.Upsert] op in ONE transaction via [OfflineEditor.edit]. There is no direct
 * [com.calypsan.listenup.client.data.remote.RpcChannel] dependency here — unlike
 * [SeriesEditRepositoryImpl]'s `mergeSeries`, entities have no online-only RPC surface; the
 * outbox sender ([com.calypsan.listenup.client.di.clientSyncModule]) dispatches every op.
 *
 * **Writes are serialized through [editMutex].** The read → build-snapshot → edit sequence is a
 * read-modify-write over the WHOLE aggregate: two concurrent edits to the same entity that both
 * read the pre-edit snapshot would each queue a payload missing the other's change, and the
 * second op's server apply (then its ServerWins echo) would silently erase the first — both
 * having returned Success. One coarse repository-level mutex closes that window. Coarse rather
 * than per-entity-id: entity curation is low-throughput human editing, so cross-entity
 * contention is negligible and the single lock avoids per-id map bookkeeping. Every write op
 * takes it — including [createEntity] (fresh state) and [deleteEntity] (no aggregate read),
 * which don't strictly need it — because "all entity writes are serialized" is a simpler
 * invariant to hold than a per-method exception list.
 */
internal class EntityEditRepositoryImpl(
    private val entityDao: EntityDao,
    private val offlineEditor: OfflineEditor,
) : EntityEditRepository {
    /** Serializes every write's read → build-snapshot → edit sequence; see the class KDoc. */
    private val editMutex = Mutex()

    override fun observeEntitiesForSeries(seriesId: String): Flow<List<Entity>> =
        entityDao.observeForSeries(seriesId).map { entities -> entities.map { it.toDomain() } }

    override fun observeEntitiesForBook(bookId: String): Flow<List<Entity>> =
        entityDao.observeForBook(bookId).map { entities -> entities.map { it.toDomain() } }

    override fun observeEntity(id: String): Flow<Entity?> =
        entityDao.observeById(id).map { entity -> entity?.toDomain() }

    override suspend fun createEntity(
        kind: EntityKind,
        name: String,
        homeSeriesId: String?,
        homeBookId: String?,
    ): AppResult<String> =
        editMutex.withLock {
            validateHome(homeSeriesId, homeBookId)?.let { return@withLock AppResult.Failure(it) }
            val id = Uuid.random().toString()
            val now = currentEpochMilliseconds()
            val upsert =
                EntityUpsert(id = id, kind = kind, name = name, homeSeriesId = homeSeriesId, homeBookId = homeBookId)
            offlineEditor
                .edit(OutboxChannels.Entities, id, EntityMutation.Upsert(upsert)) {
                    entityDao.upsert(
                        EntityEntity(
                            id = id,
                            kind = kind,
                            name = name,
                            homeSeriesId = homeSeriesId,
                            homeBookId = homeBookId,
                            imageRef = null,
                            // A brand-new local-only row: revision = 0 marks it as a not-yet-synced
                            // stub, the same convention BookMirrorApply's bootstrap stubs use — any
                            // real server revision (>= 0) supersedes it on the confirming echo.
                            revision = 0,
                            deletedAt = null,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }.map { id }
        }

    override suspend fun updateCore(
        id: String,
        name: String,
        imageRef: String?,
    ): AppResult<Unit> =
        editMutex.withLock {
            val existing =
                entityDao.getById(id) ?: return@withLock AppResult.Failure(entityNotFound(id))
            val upsert =
                EntityUpsert(
                    id = id,
                    kind = existing.kind,
                    name = name,
                    homeSeriesId = existing.homeSeriesId,
                    homeBookId = existing.homeBookId,
                    imageRef = imageRef,
                )
            offlineEditor.edit(OutboxChannels.Entities, id, EntityMutation.Upsert(upsert)) {
                entityDao.upsert(
                    existing.copy(
                        name = name,
                        imageRef = imageRef,
                        // revision + updatedAt deliberately untouched.
                    ),
                )
            }
        }

    override suspend fun deleteEntity(id: String): AppResult<Unit> =
        editMutex.withLock {
            val now = currentEpochMilliseconds()
            offlineEditor.edit(OutboxChannels.Entities, id, EntityMutation.Delete, op = OpKind.Delete) {
                // Revision preserved (not bumped) so the server's authoritative tombstone echo
                // still re-applies through the revision guard on drain — the Series pattern.
                entityDao.getById(id)?.let { entityDao.softDelete(id = id, deletedAt = now, revision = it.revision) }
            }
        }
}

/**
 * Entities are dual-homed: exactly one of [homeSeriesId] / [homeBookId] must be non-null — see
 * [com.calypsan.listenup.api.sync.EntitySyncPayload] for the dual-home rule. Returns null when
 * that rule holds, a [ValidationError] otherwise. Mirrors the server-side
 * `EntityServiceImpl.validateHome` guard word-for-word, so the same violation reads identically
 * whether it's caught locally (offline) or echoed back from the server.
 */
private fun validateHome(
    homeSeriesId: String?,
    homeBookId: String?,
): ValidationError? =
    if ((homeSeriesId == null) == (homeBookId == null)) {
        ValidationError(message = "Exactly one of homeSeriesId or homeBookId must be set.")
    } else {
        null
    }

/** Client-local not-found failure for an entity op whose target row is absent from Room. */
private fun entityNotFound(id: String): SyncError.NotFound =
    SyncError.NotFound(domain = SyncDomains.ENTITIES.name, entityId = id)

private fun EntityEntity.toDomain(): Entity =
    Entity(
        id = id,
        kind = kind,
        name = name,
        homeSeriesId = homeSeriesId,
        homeBookId = homeBookId,
        imageRef = imageRef,
    )
