package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.BioEntryPayload
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BioEntryEntity
import com.calypsan.listenup.client.data.local.db.EntityEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase

/**
 * The `entities` domain (Story World Stage 2): server-wins apply, soft-delete tombstones, full
 * digest, outbox-backed writes.
 *
 * **Outbox writes.** Every write — including a brand-new (client-minted id) entity — writes Room
 * optimistically and queues a durable op on [OutboxChannels.Entities] keyed by the entity id; the
 * entity-level in-flight shield defers a stale echo until the queued op drains. There is no
 * online-only entity RPC: create/update/bio-entry edits/delete are all offline-first, since the
 * backing [com.calypsan.listenup.api.EntityService.upsertEntity] mints no server-side identity
 * the client couldn't already generate.
 *
 * **Whole-aggregate bio entries.** [EntityMirrorApply.upsert] replaces every
 * `entity_bio_entries` row for the entity (delete-then-insert) as part of the same apply — the
 * same child-set-replace pattern [BookMirrorApply] uses for `chapters`.
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

/** Room mapping for [EntitySyncPayload] payloads (whole-aggregate bio-entry replace). */
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
                imageRef = payload.imageRef,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                createdAt = payload.createdAt,
                updatedAt = payload.updatedAt,
            ),
        )
        applyBioEntries(payload.id, payload.bioEntries)
    }

    /** Replace-wholesale: delete every existing bio entry for [entityId], then insert [entries]. */
    private suspend fun applyBioEntries(
        entityId: String,
        entries: List<BioEntryPayload>,
    ) {
        database.bioEntryDao().deleteForEntity(entityId)
        if (entries.isEmpty()) return
        database.bioEntryDao().upsertAll(
            entries.map { entry ->
                BioEntryEntity(
                    id = entry.id,
                    entityId = entityId,
                    bookId = entry.bookId,
                    positionMs = entry.positionMs,
                    mode = entry.mode,
                    text = entry.text,
                    sortKey = entry.sortKey,
                )
            },
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
