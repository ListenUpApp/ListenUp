package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.BookRatingSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BookRatingEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.sync.TargetedFetch
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession

/**
 * The `book_ratings` domain: one listener's rating of one book, mirrored under the server's opaque
 * wire id. Server-wins apply, soft tombstones, full digest, outbox writes (coalesced — a rating is
 * terminal state), and the book access gate every book-scoped domain carries, so a rating on a book
 * that leaves the viewer's scope is pruned locally rather than lingering.
 *
 * **Outbox key.** The listener's ops are keyed by bookId, not by the row's wire id: the server may
 * re-id a row, and per-book coalescing and FIFO must survive that. So the shield keys an inbound
 * rating by its book — only when it is [authSession]'s own, since another listener's rating of the
 * same book is not what the queued edit will echo — and a drained op re-reads its book's ratings.
 */
internal fun bookRatingsDomain(
    database: ListenUpDatabase,
    authSession: AuthSession,
): MirroredDomain<BookRatingSyncPayload> {
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
        outboxKeying =
            OutboxKeying(
                keysOf = { payload ->
                    val me = (authSession.authState.value as? AuthState.Authenticated)?.run { userId.value }
                    if (me != null && payload.userId == me) setOf(payload.bookId) else emptySet()
                },
                refetchFor = { bookId -> TargetedFetch.ByBookIds(listOf(bookId)) },
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
