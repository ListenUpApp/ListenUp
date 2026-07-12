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
 * `(collectionId, bookId)` primary key mirrored under the server's synthetic
 * `"$collectionId:$bookId"` envelope id (see `CollectionBookId.asString()`
 * server-side). Server-wins apply, soft tombstones (junction rule: row + revision
 * kept for digest), full digest, online-only writes, access-gated.
 *
 * **Access gate:** membership rows follow their collection's accessibility;
 * [AccessGate.liveIds] returns synthetic wire-form ids via `liveSyntheticIds()`,
 * and pruning tombstones rows outside the accessible set.
 *
 * **Re-add semantics.** Re-adding a book arrives as Created/Updated with
 * `deletedAt = null`; the upsert clears the tombstone. An own-echo needs no
 * shield: `@Upsert` is idempotent.
 */
internal fun collectionBooksDomain(database: ListenUpDatabase): MirroredDomain<CollectionBookSyncPayload> {
    val apply = CollectionBookMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.COLLECTION_BOOKS,
        apply = apply,
        conflict =
            ConflictPolicy.ServerWins(
                RevisionGuard { id ->
                    val parts = id.split(":")
                    if (parts.size != 2) {
                        null
                    } else {
                        database.collectionBookDao().revisionOf(collectionId = parts[0], bookId = parts[1])
                    }
                },
            ),
        deletes = DeleteSemantics.SoftDelete(apply::tombstoneById),
        digest = fullDigest(database.collectionBookDao()::digestRows),
        writes = WriteTier.OnlineOnly,
        accessGate =
            AccessGate(
                liveIds = database.collectionBookDao()::liveSyntheticIds,
                tombstoneByIds = database.collectionBookDao()::tombstoneByIds,
                // Fetched by the scope's collection ids. The candidate set is the local live
                // membership rows whose collection is in scope — derived from the synthetic
                // `"$collectionId:$bookId"` wire id — so a membership in a collection the delta never
                // named can never be tombstoned.
                delta =
                    AccessDeltaPolicy.Targeted(
                        order = 1,
                        axis = ScopeAxis.Collections,
                        fetchFor = { TargetedFetch.ByCollectionIds(it) },
                        candidatesFor = { collectionIds ->
                            val scopeCols = collectionIds.toSet()
                            database
                                .collectionBookDao()
                                .liveSyntheticIds()
                                .filterTo(mutableSetOf()) { it.substringBefore(':') in scopeCols }
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
                createdAt = payload.createdAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /**
     * Tombstone from an SSE `Deleted` frame (`"$collectionId:$bookId"` envelope id;
     * `:` is unambiguous — both parts are UUIDv7 strings). Unlike book_tags, this
     * DAO's tombstone advances the stored revision.
     */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
        revision: Long,
    ) {
        val parts = id.split(":")
        if (parts.size != 2) {
            logger.warn {
                "collection_books Deleted event has unexpected id format: '$id' — skipping tombstone"
            }
            return
        }
        database.collectionBookDao().tombstone(
            collectionId = parts[0],
            bookId = parts[1],
            deletedAt = deletedAt,
            revision = revision,
        )
    }

    override suspend fun tombstoneFromItem(item: CollectionBookSyncPayload) {
        database.collectionBookDao().tombstone(
            collectionId = item.collectionId,
            bookId = item.bookId,
            deletedAt = item.deletedAt ?: item.createdAt,
            revision = item.revision,
        )
    }
}
