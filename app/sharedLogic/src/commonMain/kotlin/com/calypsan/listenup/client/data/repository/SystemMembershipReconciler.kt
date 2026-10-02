package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import com.calypsan.listenup.client.data.local.db.CollectionBookEntity
import kotlin.uuid.Uuid

/**
 * The client's mirror of the server's `CollectionServiceImpl.reconcileSystemMembership`: a book is
 * in its library's All Books **exactly while** it has no live normal membership and is not held.
 *
 * The server re-derives that after every membership write; its echo carries the result. Without a
 * local mirror, a write that leaves a book in no normal collection — Release from the inbox, or
 * removing its last collection — leaves it in no collection at all until the echo lands, which the
 * visibility classifier rightly reads as *Stranded, hidden from all members*. Offline, that lie lasts
 * for the whole outage. So every local membership write ends with [reconcileLocally] for the books it
 * touched, and the device shows what the server is about to make true.
 *
 * Per book, the same three outcomes as the server:
 *  - no live normal membership and not held → All Books is made live (revived, or written fresh);
 *  - a live normal membership → All Books is tombstoned, and a held book is released (curating a held
 *    book IS releasing it);
 *  - held with no normal membership → left alone: it is awaiting triage.
 *
 * **Revisions.** These are local-only writes — no outbox op of their own — so they must neither be
 * undone by an *older* frame nor block the server's own echo:
 *  - a revived or tombstoned All Books row moves to `revision + 1`. The [RevisionGuard][com.calypsan.listenup.client.data.sync.domains.RevisionGuard]
 *    skips anything strictly older, so a replayed frame or in-flight catch-up page at the revision
 *    the device last saw (`R`) cannot flip it back. The server's own write lands at a fresh global
 *    revision, strictly above `R`, so at least `R + 1`, and an equal revision applies — the echo
 *    always converges it. Keeping `R` (as the inbox write-through does) would let that equal-revision
 *    replay through; resetting to 0 would let any older frame through.
 *  - a row the device has never seen is written at `revision = 0` under a client-minted sync id.
 *    Tombstone frames match by sync id, so no older frame can name it; the server's row arrives
 *    under its own id, the guard has no local revision for that id and applies it, and the upsert
 *    replaces the stub by its `(collectionId, bookId)` key.
 *  - the INBOX drop on curation is the inbox's own write-through
 *    ([CollectionBookDao.tombstoneHeldRows]), with its documented revision choice, so a held book
 *    leaves the inbox the same way whichever screen released it.
 *
 * **Library lookup.** All Books is per library: the book's `books.libraryId` picks the live system
 * collection of that library that is not the inbox. When the book or that collection has not synced,
 * there is nothing honest to write and the book reads Stranded until the echo — the fallback.
 *
 * Several rows per book: the caller owns the transaction (`TransactionRunner.atomically`, or the
 * offline editor's), so observers never see a book between its two writes.
 */
internal class SystemMembershipReconciler(
    private val collectionBookDao: CollectionBookDao,
) {
    /** Re-derive the system memberships of each of [bookIds] from its local rows; [now] stamps new rows. */
    suspend fun reconcileLocally(
        bookIds: Collection<String>,
        now: Long,
    ) {
        for (bookId in bookIds) reconcileBook(bookId, now)
    }

    private suspend fun reconcileBook(
        bookId: String,
        now: Long,
    ) {
        val held = collectionBookDao.isHeld(bookId)
        if (collectionBookDao.hasLiveNormalMembership(bookId)) {
            collectionBookDao.tombstoneAllBooksRowsLocally(bookId, now)
            if (held) collectionBookDao.tombstoneHeldRows(listOf(bookId), now)
            return
        }
        if (held) return
        val allBooksId = collectionBookDao.allBooksCollectionIdForBook(bookId) ?: return
        val existing = collectionBookDao.findByKey(allBooksId, bookId)
        if (existing == null) {
            collectionBookDao.upsert(
                CollectionBookEntity(
                    collectionId = allBooksId,
                    bookId = bookId,
                    syncId = Uuid.random().toString(),
                    createdAt = now,
                    revision = 0,
                    deletedAt = null,
                ),
            )
        } else if (existing.deletedAt != null) {
            collectionBookDao.reviveLocally(allBooksId, bookId)
        }
    }
}
