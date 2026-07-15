package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.EntityEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase

/**
 * The `entities` domain (Story World Stage 2, dual-home amendment): server-wins apply,
 * soft-delete tombstones, full digest, outbox-backed writes.
 *
 * **Outbox writes.** Every write — including a brand-new (client-minted id) entity — writes Room
 * optimistically and queues a durable op on [OutboxChannels.Entities] keyed by the entity id; the
 * entity-level in-flight shield defers a stale echo until the queued op drains. There is no
 * online-only entity RPC: create/update/delete are all offline-first, since the backing
 * [com.calypsan.listenup.api.EntityService.upsertEntity] mints no server-side identity the
 * client couldn't already generate.
 *
 * **Plain aggregate.** [EntityMirrorApply.upsert] is a single-row upsert — an entity has no
 * child collection to replace, mirroring [SeriesMirrorApply].
 */
internal fun entitiesDomain(database: ListenUpDatabase): MirroredDomain<EntitySyncPayload> {
    val apply = EntityMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.ENTITIES,
        apply = apply,
        conflict = ConflictPolicy.ServerWins(RevisionGuard { id -> database.entityDao().revisionOf(id) }),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.entityDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.Entities),
    )
}

/** Room mapping for [EntitySyncPayload] payloads (plain single-row upsert). */
internal class EntityMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<EntitySyncPayload> {
    override suspend fun upsert(payload: EntitySyncPayload) {
        database.entityDao().upsert(
            EntityEntity(
                id = payload.id,
                kind = payload.kind,
                name = payload.name,
                homeSeriesId = payload.homeSeriesId,
                homeBookId = payload.homeBookId,
                imageRef = payload.imageRef,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                createdAt = payload.createdAt,
                updatedAt = payload.updatedAt,
            ),
        )
    }

    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.entityDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: EntitySyncPayload) {
        tombstoneById(
            id = item.id,
            deletedAt = item.deletedAt ?: item.updatedAt,
            revision = item.revision,
        )
    }
}
