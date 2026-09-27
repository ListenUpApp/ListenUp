package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BookRatingEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.TargetedFetch

/**
 * The `book_ratings` domain: one listener's rating of one book, mirrored under the server's opaque
 * wire id. Server-wins apply, soft tombstones, full digest, outbox writes (coalesced — a rating is
 * terminal state), and the book access gate every book-scoped domain carries, so a rating on a book
 * that leaves the viewer's scope is pruned locally rather than lingering.
 */
internal fun bookRatingsDomain(database: ListenUpDatabase): MirroredDomain<BookRatingSyncPayload> {
    val apply = BookRatingMirrorApply(database)
    return MirroredDomain(
        key = SyncDomains.BOOK_RATINGS,
        apply = apply,
        conflict =
            ConflictPolicy.ServerWins(
                RevisionGuard { syncId ->
                    database.bookRatingDao().revisionOfSyncId(syncId)
                },
            ),
        deletes = DeleteSemantics.SoftDelete { id, deletedAt, _ -> apply.tombstoneById(id, deletedAt) },
        digest = fullDigest(database.bookRatingDao()::digestRows),
        writes = WriteTier.Outbox(OutboxChannels.BookRatings),
        accessGate =
            AccessGate(
                liveIds = database.bookRatingDao()::liveIds,
                tombstoneByIds = database.bookRatingDao()::tombstoneByIds,
                delta =
                    AccessDeltaPolicy.Targeted(
                        order = 5,
                        axis = ScopeAxis.Books,
                        fetchFor = { TargetedFetch.ByBookIds(it) },
                        candidatesFor = { bookIds ->
                            bookIds
                                .chunked(SQLITE_IN_CHUNK)
                                .flatMapTo(mutableSetOf()) { database.bookRatingDao().liveSyncIdsForBooks(it) }
                        },
                    ),
            ),
    )
}

/** Room mapping for [BookRatingSyncPayload]. */
internal class BookRatingMirrorApply(
    private val database: ListenUpDatabase,
) : MirrorApply<BookRatingSyncPayload> {
    override suspend fun upsert(payload: BookRatingSyncPayload) {
        database.bookRatingDao().upsert(
            BookRatingEntity(
                bookId = payload.bookId,
                userId = payload.userId,
                syncId = payload.id,
                halfStars = payload.halfStars,
                note = payload.note,
                ratedAt = payload.ratedAt,
                updatedAt = payload.updatedAt,
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
        database.bookRatingDao().tombstoneBySyncId(id, deletedAt)
    }

    /** Tombstones ship with the pair blanked, so they apply by wire id only. */
    override suspend fun tombstoneFromItem(item: BookRatingSyncPayload) {
        database.bookRatingDao().tombstoneBySyncId(syncId = item.id, deletedAt = item.deletedAt ?: item.updatedAt)
    }
}
