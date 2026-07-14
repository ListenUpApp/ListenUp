package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.cover.StoredCoverInfo
import com.calypsan.listenup.server.db.sqldelight.TransactionLocal
import com.calypsan.listenup.server.db.sqldelight.currentTransactionLocal

/**
 * Per-write extras the scan/edit paths inject into [BookRepository.writePayload] via the
 * coroutine context. Replaces shared-mutable maps: scoped to the call, no cross-book race.
 *
 * The consumer — the SQLDelight base's non-suspend `writePayload`, which runs synchronously inside
 * `suspendTransaction { db.transactionWithResult { … } }` — cannot read the suspend-only
 * `coroutineContext`. So callers install the extras with `withContext(TransactionLocal(extras)) { … }`:
 * [suspendTransaction][com.calypsan.listenup.server.db.sqldelight.suspendTransaction] mirrors the
 * carried value onto whatever thread the transaction body runs on (after the `sqlIoDispatcher` hop),
 * and `writePayload` reads it back via [current] with no suspension and no cross-coroutine race.
 */
class BookWriteExtras(
    val managedCover: StoredCoverInfo? = null,
    /**
     * The library's target system collection id (pure-union membership model), set only when a
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
    /**
     * Pre-resolved genre ids for this book (#batched-scan persist). Non-null only on the batched
     * scan-persist path, where every distinct raw genre string was resolved ONCE up front (alias →
     * normalize → auto-create) in the suspend prepare phase. When present,
     * [BookRepository.writePayload] writes the `book_genres` junctions IN the same SQLDelight
     * transaction as the book row (via [BookGenreWriter.writeJunctions]) instead of the per-book
     * post-commit `processGenreStrings` pass — so a genred book is one commit, not ~6. Null on every
     * single-book path (metadata apply / `setBookGenres`), which keeps the separate `processGenreStrings`
     * call. An empty list is meaningful: it wipes the book's genres (a rescan that dropped every string).
     */
    val genreIds: List<String>? = null,
    /**
     * Per-field provenance preserve flags (rescan data-safety). True when the stored book carries a
     * contributor / series value that out-ranks a scan (enrichment or user) and THIS scan write isn't
     * itself re-editing it — computed by the scan paths' merge (`mergeByProvenance`, the tier rule).
     * When set, [BookRepository.writePayload] skips the corresponding `replace` so the higher-tier
     * contributor/series rows survive the rescan, exactly as `chapter_source = 'user'` protects
     * chapters. Scalar fields (title/subtitle/description) are preserved on the payload itself, so
     * they need no flag. Every non-scan write path leaves these false (replace as normal).
     */
    val preserveContributors: Boolean = false,
    val preserveSeries: Boolean = false,
) {
    companion object {
        /** The extras active on the current transaction thread, or null when none is installed. */
        fun current(): BookWriteExtras? = currentTransactionLocal() as? BookWriteExtras
    }
}
