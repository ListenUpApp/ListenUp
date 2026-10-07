package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.EntityEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.TargetedFetch

/**
 * The `entities` domain (Story World): server-wins apply, soft tombstones, full digest, outbox writes, and
 * the book access gate — an entity whose home book leaves the viewer's scope, or whose home series no
 * longer holds a book the viewer can see, is pruned locally.
 *
 * **Targeted delta.** The scoped `AccessChanged` asks by book id; the server resolves those books to the
 * entities they touch (`EntityRepository.pullByIds`) and answers only the visible ones, and
 * [com.calypsan.listenup.client.data.local.db.EntityDao.liveIdsTouchingBooks] is the same set locally — so
 * a series-homed entity is kept while any of its series' books stays visible, and pruned once none does.
 *
 * **Tombstones** arrive with the kind blanked to UNKNOWN and no home. They apply by id only
 * ([EntityMirrorApply.tombstoneById]), so the local row keeps its kind and name for undo.
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
        accessGate =
            AccessGate(
                liveIds = database.entityDao()::liveIds,
                tombstoneByIds = database.entityDao()::tombstoneByIds,
                delta =
                    AccessDeltaPolicy.Targeted(
                        order = ENTITIES_DELTA_ORDER,
                        axis = ScopeAxis.Books,
                        fetchFor = { TargetedFetch.ByBookIds(it) },
                        candidatesFor = { bookIds ->
                            bookIds
                                .chunked(SQLITE_IN_CHUNK)
                                .flatMapTo(mutableSetOf()) { database.entityDao().liveIdsTouchingBooks(it, it) }
                        },
                    ),
            ),
    )
}

/** After every existing book-keyed domain (book_external_ratings is 6): entities depend on book access only. */
private const val ENTITIES_DELTA_ORDER = 7

/** Room mapping for [EntitySyncPayload]. */
internal class EntityMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<EntitySyncPayload> {
    override suspend fun upsert(payload: EntitySyncPayload) {
        database.entityDao().upsert(
            EntityEntity(
                id = payload.id,
                kind = payload.kind,
                name = payload.name,
                descriptor = payload.descriptor,
                parentId = payload.parentId,
                homeSeriesId = payload.homeSeriesId,
                homeBookId = payload.homeBookId,
                imageRef = payload.imageRef,
                createdBy = payload.createdBy,
                updatedBy = payload.updatedBy,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                createdAt = payload.createdAt,
                updatedAt = payload.updatedAt,
            ),
        )
    }

    /** Tombstone from a `Deleted` frame; a no-op when no local row matches. */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        database.entityDao().softDelete(id = id, deletedAt = deletedAt, revision = revision)
    }

    override suspend fun tombstoneFromItem(item: EntitySyncPayload) {
        tombstoneById(id = item.id, deletedAt = item.deletedAt ?: item.updatedAt, revision = item.revision)
    }
}
