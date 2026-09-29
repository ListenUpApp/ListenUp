package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.ExternalRatingSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BookExternalRatingEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.TargetedFetch

/**
 * The `book_external_ratings` domain: how one outside catalog (Audible, and later Hardcover/
 * Goodreads) rates one book, mirrored under the server's opaque wire id. Server-wins apply, soft
 * tombstones, full digest, and the same book access gate every book-scoped domain carries — a row
 * on a book that leaves the viewer's scope is pruned locally rather than lingering, exactly like
 * [bookRatingsDomain].
 *
 * **No outbox.** [WriteTier.ServerOwned]: the server is the sole writer, via
 * `ExternalRatingsFetcher` on a match, a nightly sweep, or an admin's refresh request. Unlike
 * [bookRatingsDomain] there is no [MirroredDomain.outboxKeying] — a client never queues a write
 * here, so there is no outbox echo to re-key.
 */
internal fun bookExternalRatingsDomain(database: ListenUpDatabase): MirroredDomain<ExternalRatingSyncPayload> {
    val apply = BookExternalRatingMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.BOOK_EXTERNAL_RATINGS,
        apply = apply,
        conflict =
            ConflictPolicy.ServerWins(
                RevisionGuard { syncId ->
                    database.bookExternalRatingDao().revisionOfSyncId(syncId)
                },
            ),
        deletes = DeleteSemantics.SoftDelete { id, deletedAt, _ -> apply.tombstoneById(id, deletedAt) },
        digest = fullDigest(database.bookExternalRatingDao()::digestRows),
        writes = WriteTier.ServerOwned,
        accessGate =
            AccessGate(
                liveIds = database.bookExternalRatingDao()::liveIds,
                tombstoneByIds = database.bookExternalRatingDao()::tombstoneByIds,
                delta =
                    AccessDeltaPolicy.Targeted(
                        order = 6,
                        axis = ScopeAxis.Books,
                        fetchFor = { TargetedFetch.ByBookIds(it) },
                        candidatesFor = { bookIds ->
                            bookIds
                                .chunked(SQLITE_IN_CHUNK)
                                .flatMapTo(mutableSetOf()) { database.bookExternalRatingDao().liveSyncIdsForBooks(it) }
                        },
                    ),
            ),
    )
}

/** Room mapping for [ExternalRatingSyncPayload]. */
internal class BookExternalRatingMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<ExternalRatingSyncPayload> {
    override suspend fun upsert(payload: ExternalRatingSyncPayload) {
        database.bookExternalRatingDao().upsert(
            BookExternalRatingEntity(
                bookId = payload.bookId,
                source = payload.source.name,
                syncId = payload.id,
                average = payload.average,
                count = payload.count,
                enabled = payload.enabled,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }

    /** Tombstone from a `Deleted` frame by wire id; a no-op when no local row matches. */
    suspend fun tombstoneById(
        id: String,
        deletedAt: Long,
    ) {
        database.bookExternalRatingDao().tombstoneBySyncId(id, deletedAt)
    }

    /**
     * Tombstones ship with the pair blanked, so they apply by wire id only.
     *
     * Unlike every other domain's [tombstoneFromItem], there is no `?: item.updatedAt`/
     * `?: item.createdAt` fallback here: [ExternalRatingSyncPayload] carries no timestamp field
     * besides [ExternalRatingSyncPayload.deletedAt] itself, and this method is only ever reached
     * for an item [SyncCatchUpClient] has already identified as a tombstone (`deletedAt != null`)
     * — see [MirrorApply.tombstoneFromItem]'s KDoc.
     */
    override suspend fun tombstoneFromItem(item: ExternalRatingSyncPayload) {
        database.bookExternalRatingDao().tombstoneBySyncId(
            syncId = item.id,
            deletedAt =
                checkNotNull(item.deletedAt) {
                    "catch-up tombstone for book_external_ratings/${item.id} arrived with deletedAt null"
                },
        )
    }
}
