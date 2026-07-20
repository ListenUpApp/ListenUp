package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.CollectionBookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.TargetedFetch
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * The `collection_books` junction domain (Collections — Room v24): composite
 * `(collectionId, bookId)` primary key, mirrored under the server's opaque per-row wire id
 * (SERVER-SYNC-04 — stored as [CollectionBookEntity.syncId], matched by identity, never parsed).
 * Server-wins apply, soft tombstones (junction rule: row + revision kept for digest), full digest,
 * outbox-backed writes, access-gated.
 *
 * **Access gate:** membership rows follow their collection's accessibility;
 * [AccessGate.liveIds] returns opaque wire-form ids via `liveSyncIds()`, and pruning
 * tombstones rows outside the accessible set.
 *
 * **Re-add semantics.** Re-adding a book arrives as Created/Updated with
 * `deletedAt = null`; the upsert clears the tombstone.
 *
 * **Outbox writes.** Adding and removing a book write the junction optimistically and queue a
 * durable op on [OutboxChannels.CollectionBooks]; the in-flight shield defers the junction's own
 * echo until that op drains. Both are offline-first — the book already exists, so no server id is
 * minted.
 */
internal fun collectionBooksDomain(database: ListenUpDatabase): MirroredDomain<CollectionBookSyncPayload> {
    val apply = CollectionBookMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.COLLECTION_BOOKS,
        apply = apply,
        conflict =
            ConflictPolicy.ServerWins(
                RevisionGuard { syncId ->
                    database.collectionBookDao().revisionOfSyncId(syncId)
                },
            ),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.collectionBookDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.CollectionBooks),
        accessGate =
            AccessGate(
                liveIds = database.collectionBookDao()::liveSyncIds,
                tombstoneByIds = database.collectionBookDao()::tombstoneByIds,
                // Fetched by the scope's collection ids. The candidate set is the local live
                // membership rows whose collection is in scope — a real column predicate now that
                // the opaque wire id (SERVER-SYNC-04) no longer encodes collectionId.
                delta =
                    AccessDeltaPolicy.Targeted(
                        order = 1,
                        axis = ScopeAxis.Collections,
                        fetchFor = { TargetedFetch.ByCollectionIds(it) },
                        candidatesFor = { collectionIds ->
                            database.collectionBookDao().liveSyncIdsForCollections(collectionIds).toSet()
                        },
                    ),
            ),
    )
}

/** Room mapping for [CollectionBookSyncPayload] junction payloads. */
internal class CollectionBookMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<CollectionBookSyncPayload> {
    override suspend fun upsert(payload: CollectionBookSyncPayload) {
        database.collectionBookDao().upsert(
            CollectionBookEntity(
                collectionId = payload.collectionId,
                bookId = payload.bookId,
                syncId = payload.id,
                createdAt = payload.createdAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /**
     * Tombstone from an SSE `Deleted` frame by the opaque wire [id] (SERVER-SYNC-04) — a graceful
     * no-op if [id] matches no local row (nothing to reconcile locally). Unlike book_tags, this
     * DAO's tombstone advances the stored revision.
     */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        val affected = database.collectionBookDao().tombstoneBySyncId(id, deletedAt, revision)
        if (affected == 0) {
            logger.debug { "collection_books Deleted event matched no local row for id='$id' — graceful no-op" }
        }
    }

    /**
     * Tombstone from a catch-up item. The pull path blanks [item]'s natural pair on a tombstone
     * (SERVER-SYNC-04 — junction tombstones ship identity only), so this applies by [item]'s
     * opaque wire id, never by `collectionId`/`bookId`.
     */
    override suspend fun tombstoneFromItem(item: CollectionBookSyncPayload) {
        database.collectionBookDao().tombstoneBySyncId(
            syncId = item.id,
            deletedAt = item.deletedAt ?: item.createdAt,
            revision = item.revision,
        )
    }
}
