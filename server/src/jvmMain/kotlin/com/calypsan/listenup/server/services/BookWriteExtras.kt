package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.cover.StoredCoverInfo
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/**
 * Per-write extras the scan/edit paths inject into [BookRepository.writePayload] via the
 * coroutine context. Replaces shared-mutable maps: scoped to the call, no cross-book race.
 *
 * Implemented as a [ThreadContextElement] (not a plain context element) because the
 * consumer — the SQLDelight base's non-suspend `writePayload`, which runs synchronously
 * inside `withContext(sqlIoDispatcher) { db.transactionWithResult { … } }` — cannot read the
 * suspend-only `coroutineContext`. The [ThreadContextElement] mirrors this element into a
 * thread-local on whatever thread the coroutine resumes on (including after the dispatch hop
 * into the SQL I/O dispatcher), so `writePayload` reads it via [current] with no suspension
 * and no cross-coroutine field race: each coroutine installs/restores its own value around
 * every resume.
 */
class BookWriteExtras(
    val managedCover: StoredCoverInfo? = null,
    /**
     * The library's target system collection id (#680 pure-union model), set only when a
     * genuinely-new book must be auto-assigned to a system collection: ALL_BOOKS when the inbox
     * gate is off (immediately member-visible via the default grant), or INBOX when on
     * (quarantined, admin-only). The two are mutually exclusive — a held book joins INBOX only.
     * Non-null → [BookRepository.writePayload]'s INSERT branch writes the `collection_books`
     * membership row **inside the same SQLDelight transaction** as the book row, so a held book is
     * never momentarily pullable by a member (the access filter already sees it collected before
     * any `book.Created` is delivered). The scanner sets this via
     * `resolveOrInsert(systemCollectionId = …)`; every other write path leaves it null.
     */
    val systemCollectionId: String? = null,
    /**
     * Edit-path override for the book's `createdAt` (the "added date"). Non-null only when an
     * explicit metadata edit re-stamps the added date — [BookRepository.writePayload]'s UPDATE
     * branch writes it through. The scanner never sets this.
     */
    val createdAtOverride: Long? = null,
) : ThreadContextElement<BookWriteExtras?> {
    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): BookWriteExtras? {
        val previous = threadLocal.get()
        threadLocal.set(this)
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: BookWriteExtras?,
    ) {
        threadLocal.set(oldState)
    }

    companion object Key : CoroutineContext.Key<BookWriteExtras> {
        private val threadLocal = ThreadLocal<BookWriteExtras?>()

        /** The extras active on the current thread, or null when no [BookWriteExtras] is installed. */
        fun current(): BookWriteExtras? = threadLocal.get()
    }
}
