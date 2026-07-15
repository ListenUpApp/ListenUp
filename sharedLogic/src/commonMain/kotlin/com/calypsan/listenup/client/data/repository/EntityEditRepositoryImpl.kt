package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.entity.EntityMutation
import com.calypsan.listenup.api.dto.entity.EntityUpsert
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.BioEntryPayload
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.client.data.local.db.BioEntryDao
import com.calypsan.listenup.client.data.local.db.BioEntryEntity
import com.calypsan.listenup.client.data.local.db.EntityDao
import com.calypsan.listenup.client.data.local.db.EntityEntity
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.domains.OpKind
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.model.BioEntry
import com.calypsan.listenup.client.domain.model.Entity
import com.calypsan.listenup.client.domain.repository.EntityEditRepository
import com.calypsan.listenup.core.currentEpochMilliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

private const val ENTITIES_DOMAIN = "entities"

/**
 * Offline-first Story World entity editor.
 *
 * Every write reads the entity's CURRENT Room state (outside the transaction, mirroring
 * [BookEditRepositoryImpl.setBookChapters]'s pre-validation read), builds the full-aggregate
 * [EntityUpsert] snapshot, then applies the optimistic Room merge and enqueues the durable
 * [EntityMutation.Upsert] op in ONE transaction via [OfflineEditor.edit]. There is no direct
 * [com.calypsan.listenup.client.data.remote.RpcChannel] dependency here — unlike
 * [SeriesEditRepositoryImpl]'s `mergeSeries`, entities have no online-only RPC surface; the
 * outbox sender ([com.calypsan.listenup.client.di.clientSyncModule]) dispatches every op.
 */
internal class EntityEditRepositoryImpl(
    private val entityDao: EntityDao,
    private val bioEntryDao: BioEntryDao,
    private val offlineEditor: OfflineEditor,
) : EntityEditRepository {
    override fun observeEntitiesForSeries(seriesId: String): Flow<List<Entity>> =
        entityDao.observeForSeries(seriesId).map { entities -> entities.map { it.toDomain() } }

    override fun observeEntity(id: String): Flow<Entity?> =
        combine(entityDao.observeById(id), bioEntryDao.observeForEntity(id)) { entity, entries ->
            entity?.toDomain(entries.map { it.toDomain() })
        }

    override suspend fun createEntity(
        kind: EntityKind,
        name: String,
        homeSeriesId: String,
    ): AppResult<String> {
        val id = Uuid.random().toString()
        val now = currentEpochMilliseconds()
        val upsert = EntityUpsert(id = id, kind = kind, name = name, homeSeriesId = homeSeriesId)
        return offlineEditor
            .edit(OutboxChannels.Entities, id, EntityMutation.Upsert(upsert)) {
                entityDao.upsert(
                    EntityEntity(
                        id = id,
                        kind = kind,
                        name = name,
                        homeSeriesId = homeSeriesId,
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
    ): AppResult<Unit> {
        val existing = entityDao.getById(id) ?: return AppResult.Failure(entityNotFound(id))
        val bioEntries = bioEntryDao.getForEntity(id).map { it.toPayload() }
        val upsert =
            EntityUpsert(
                id = id,
                kind = existing.kind,
                name = name,
                homeSeriesId = existing.homeSeriesId,
                imageRef = imageRef,
                bioEntries = bioEntries,
            )
        return offlineEditor.edit(OutboxChannels.Entities, id, EntityMutation.Upsert(upsert)) {
            entityDao.upsert(existing.copy(name = name, imageRef = imageRef))
        }
    }

    override suspend fun upsertBioEntry(
        entityId: String,
        entry: BioEntry,
    ): AppResult<Unit> {
        val existing = entityDao.getById(entityId) ?: return AppResult.Failure(entityNotFound(entityId))
        val entryId = entry.id.ifBlank { Uuid.random().toString() }
        val mergedEntry = entry.copy(id = entryId)
        val mergedEntries =
            bioEntryDao
                .getForEntity(entityId)
                .map { it.toPayload() }
                .filterNot { it.id == entryId } + mergedEntry.toPayload()
        val upsert =
            EntityUpsert(
                id = entityId,
                kind = existing.kind,
                name = existing.name,
                homeSeriesId = existing.homeSeriesId,
                imageRef = existing.imageRef,
                bioEntries = mergedEntries,
            )
        return offlineEditor.edit(OutboxChannels.Entities, entityId, EntityMutation.Upsert(upsert)) {
            bioEntryDao.upsertAll(listOf(mergedEntry.toEntity(entityId)))
        }
    }

    override suspend fun removeBioEntry(
        entityId: String,
        entryId: String,
    ): AppResult<Unit> {
        val existing = entityDao.getById(entityId) ?: return AppResult.Failure(entityNotFound(entityId))
        val remainingEntries =
            bioEntryDao
                .getForEntity(entityId)
                .filterNot { it.id == entryId }
                .map { it.toPayload() }
        val upsert =
            EntityUpsert(
                id = entityId,
                kind = existing.kind,
                name = existing.name,
                homeSeriesId = existing.homeSeriesId,
                imageRef = existing.imageRef,
                bioEntries = remainingEntries,
            )
        return offlineEditor.edit(OutboxChannels.Entities, entityId, EntityMutation.Upsert(upsert)) {
            bioEntryDao.deleteById(entryId)
        }
    }

    override suspend fun deleteEntity(id: String): AppResult<Unit> {
        val now = currentEpochMilliseconds()
        return offlineEditor.edit(OutboxChannels.Entities, id, EntityMutation.Delete, op = OpKind.Delete) {
            entityDao.getById(id)?.let { entityDao.softDelete(id = id, deletedAt = now, revision = it.revision) }
        }
    }
}

/** Client-local not-found failure for an entity op whose target row is absent from Room. */
private fun entityNotFound(id: String): SyncError.NotFound = SyncError.NotFound(domain = ENTITIES_DOMAIN, entityId = id)

private fun EntityEntity.toDomain(bioEntries: List<BioEntry> = emptyList()): Entity =
    Entity(
        id = id,
        kind = kind,
        name = name,
        homeSeriesId = homeSeriesId,
        imageRef = imageRef,
        bioEntries = bioEntries,
    )

private fun BioEntryEntity.toDomain(): BioEntry =
    BioEntry(id = id, bookId = bookId, positionMs = positionMs, mode = mode, text = text, sortKey = sortKey)

private fun BioEntryEntity.toPayload(): BioEntryPayload =
    BioEntryPayload(id = id, bookId = bookId, positionMs = positionMs, mode = mode, text = text, sortKey = sortKey)

private fun BioEntry.toPayload(): BioEntryPayload =
    BioEntryPayload(id = id, bookId = bookId, positionMs = positionMs, mode = mode, text = text, sortKey = sortKey)

private fun BioEntry.toEntity(entityId: String): BioEntryEntity =
    BioEntryEntity(
        id = id,
        entityId = entityId,
        bookId = bookId,
        positionMs = positionMs,
        mode = mode,
        text = text,
        sortKey = sortKey,
    )
