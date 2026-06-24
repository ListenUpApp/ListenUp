package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId

/**
 * The narrow slice of [BookRepository] that [BookPersister] depends on.
 *
 * Exists so the persister's orchestration logic (per-book error containment,
 * full-scan-only tombstone sweep) can be tested against an in-memory fake
 * without standing up a database. [BookRepository] is the sole production
 * implementation.
 */
interface BookIngestPort {
    /**
     * Resolve-or-insert a scanned book; see [BookRepository.resolveOrInsert].
     *
     * [pendingCover] carries the pre-validated cover bytes + provenance extracted
     * from the [AnalyzedBook] before the call — the image file has NOT yet been
     * written to the managed store. The implementation stores the bytes to
     * [com.calypsan.listenup.server.cover.CoverImageStore] after resolving the
     * stable [BookId], so the file is always named after the book it belongs to.
     * Null means the scanned book has no cover to store (or cover storage is not
     * configured).
     *
     * [systemCollectionId], when non-null, is the id of the system collection (ALL_BOOKS
     * when the inbox gate is off, INBOX when on) resolved once per scan by the orchestrator.
     * Only when this call genuinely INSERTS a new book is its book→collection membership
     * written inside the same transaction as the book row, so the firehose (which evaluates
     * access at delivery) never exposes a held book to members. Re-scans/updates of an
     * existing book never add membership. Null leaves the new book uncollected (invisible to
     * members under the pure-union rule until an admin collects it).
     *
     * The two cases are mutually exclusive: a held book must NOT join ALL_BOOKS (doing so
     * would expose it to every member via the ALL_BOOKS grant); a non-held book must NOT
     * join INBOX (it would be quarantined unnecessarily).
     *
     * [contributorIds] / [seriesIds] are the scan-wide pre-resolved dedup-key → id maps the
     * orchestrator builds ONCE before the persist loop (see [BookRepository.upsertFromAnalyzed]),
     * collapsing the per-book contributor/series `resolveOrCreate` transaction storm into a single
     * bulk lookup. Null means "resolve each name per-call" — the path single-book callers use.
     */
    suspend fun resolveOrInsert(
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover? = null,
        systemCollectionId: String? = null,
        contributorIds: Map<String, ContributorId>? = null,
        seriesIds: Map<String, SeriesId>? = null,
    ): AppResult<IngestOutcome>

    /**
     * Batch-resolve every contributor and series referenced by [books] to a stable id, ONCE,
     * before the per-book persist loop — collapsing the per-book `resolveOrCreate` transaction
     * storm (a SELECT, plus a create txn for each new name, per contributor/series per book) into
     * one bulk SELECT per catalogue. The returned dedup-key → id maps are threaded back into each
     * [resolveOrInsert] call ([contributorIds] / [seriesIds]); a book's contributor/series id is
     * looked up from them by recomputing the same dedup key.
     *
     * The default returns empty maps — single-book callers and orchestration fakes fall back to
     * per-call resolution inside [resolveOrInsert]. [BookRepository] overrides it to do the real
     * bulk resolve through the contributor/series catalogues.
     */
    suspend fun resolveScanIdentities(books: Collection<AnalyzedBook>): ScanIdentityMaps = ScanIdentityMaps()

    /**
     * Soft-delete library books absent from [seenPaths]; see [BookRepository.softDeleteAbsentByPaths].
     *
     * Accepts the set of `rootRelPath` strings seen on disk during a full scan — no BookId
     * resolution required for books that did not change.
     */
    suspend fun softDeleteAbsentByPaths(
        libraryId: LibraryId,
        seenPaths: Set<String>,
    )

    /**
     * Soft-delete the live book at [rootRelPath] inside [libraryId], if one exists.
     *
     * Idempotent: a no-op when no live (non-deleted) book exists at that path (already
     * tombstoned or never ingested). Emits [com.calypsan.listenup.api.sync.SyncEvent.Deleted]
     * to the change bus so connected clients reflow immediately.
     *
     * Called from [com.calypsan.listenup.server.services.BookPersister] when a
     * [com.calypsan.listenup.api.dto.scanner.ChangeEventDto.Removed] arrives on an
     * incremental scan — the only path that explicitly notifies of a deletion without
     * walking the entire library.
     */
    suspend fun softDeleteByPath(
        libraryId: LibraryId,
        rootRelPath: String,
    )
}

/**
 * The scan-wide pre-resolved identity maps [BookPersister] threads through every per-book
 * [BookIngestPort.resolveOrInsert] call. [contributors] is keyed by the contributor dedup key,
 * [series] by the series normalized key — exactly the keys [BookRepository.upsertFromAnalyzed]
 * recomputes to look an id up, so a book's contributors/series resolve without a per-call SELECT.
 */
data class ScanIdentityMaps(
    val contributors: Map<String, ContributorId> = emptyMap(),
    val series: Map<String, SeriesId> = emptyMap(),
)

/**
 * Cover bytes extracted from an [AnalyzedBook] before the DB upsert, ready to
 * be stored into the managed cover store once the stable [BookId] is known.
 *
 * [bytes] are the raw image bytes; [mime] is the MIME type declared by the
 * source (e.g. `"image/jpeg"`); [source] is the sync-layer provenance tag
 * ([CoverSource.FILESYSTEM] or [CoverSource.EMBEDDED]).
 */
data class PendingCover(
    val bytes: ByteArray,
    val mime: String,
    val source: CoverSource,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingCover) return false
        return mime == other.mime && source == other.source && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = mime.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * The result of a resolve-or-insert: the stable [bookId] the aggregate landed
 * under, plus whether this scan minted it fresh ([wasNew]) versus refreshed an
 * existing book in place (a plain rescan or a move).
 *
 * [wasNew] reports a genuine fact about the operation that resolve-or-insert
 * knows for free. System-collection membership uses it as the isNew gate —
 * only genuinely new books join the resolved collection; re-scans skip membership.
 */
data class IngestOutcome(
    val bookId: BookId,
    val wasNew: Boolean,
)
