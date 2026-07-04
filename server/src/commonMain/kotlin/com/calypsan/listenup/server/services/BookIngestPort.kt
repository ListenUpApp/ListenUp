package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.cover.PendingCover
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException

private val log = loggerFor<BookIngestPort>()

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
     * Batch-persist every changed book in [books] in chunked write transactions — the
     * performance-critical counterpart to a per-book [resolveOrInsert] loop. See
     * [BookRepository.resolveOrInsertAll] for the full contract.
     *
     * Where a per-book loop costs O(books) write transactions (plus a read txn per existence lookup
     * and ~6 auto-committing junction writes per genred book), this resolves everything that needs a
     * suspend call ONCE up front — contributor/series ids (via [identityMaps]), genre ids (already in
     * [identityMaps]), cover-file stores, and bulk book-existence — then writes the books in chunks of
     * a fixed size, each chunk a single transaction whose synchronous body loops the books (each in its
     * own savepoint for per-book error containment). The result is O(chunks) write transactions.
     *
     * [coversByBook] maps a book's `rootRelPath` to its pre-extracted [PendingCover] (read off-thread
     * before the write loop). [systemCollectionId] and [identityMaps] carry the same scan-wide
     * pre-resolved values [resolveOrInsert] takes per call.
     *
     * Returns a [PersistResult] with the persisted/failed counts and the set of resolved [BookId]s, so
     * the orchestrator can stamp honest [com.calypsan.listenup.api.dto.scanner.ScanResultSummary] counts.
     *
     * [onProgress] is invoked with the running `(processed, failed)` tally after each chunk commits —
     * the orchestrator translates it into a PERSISTING [com.calypsan.listenup.api.event.ScanEvent.Progress]
     * tick. `processed` counts every book the loop has finished (persisted + failed), so the bar reaches
     * the total even when some books fail.
     *
     * Per-book error containment: a single book's typed failure or escaped exception is logged and
     * counted as failed, never aborting the rest. An [OutOfMemoryError] is NOT contained — the heap is
     * compromised, so it aborts the loop and propagates (wrapped in [PersistAbortedByOom] with the
     * partial counts so the orchestrator can still emit honest numbers). [kotlinx.coroutines.CancellationException]
     * always re-raises.
     *
     * The default loops [resolveOrInsert] per book — orchestration fakes inherit it unchanged.
     * [BookRepository] overrides it with the real chunked-transaction path.
     */
    suspend fun resolveOrInsertAll(
        libraryId: LibraryId,
        folderId: FolderId,
        books: List<AnalyzedBook>,
        coversByBook: Map<String, PendingCover>,
        systemCollectionId: String?,
        identityMaps: ScanIdentityMaps,
        onProgress: suspend (processed: Int, failed: Int) -> Unit,
    ): PersistResult {
        var persisted = 0
        var failed = 0
        val resolvedIds = mutableSetOf<BookId>()
        for (book in books) {
            val outcome =
                try {
                    resolveOrInsert(
                        libraryId,
                        folderId,
                        book,
                        coversByBook[book.candidate.rootRelPath],
                        systemCollectionId,
                        identityMaps.contributors,
                        identityMaps.series,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    failed++
                    throw PersistAbortedByOom(PersistResult(persisted, failed, resolvedIds), e)
                } catch (e: Throwable) {
                    log.warn(e) { "Book persist threw: ${book.candidate.rootRelPath} — continuing" }
                    AppResult.Failure(SyncError.NotFound(domain = "books", entityId = book.candidate.rootRelPath))
                }
            when (outcome) {
                is AppResult.Success -> {
                    persisted++
                    resolvedIds += outcome.data.bookId
                }

                is AppResult.Failure -> {
                    failed++
                }
            }
            onProgress(persisted + failed, failed)
        }
        return PersistResult(persisted = persisted, failed = failed, resolvedIds = resolvedIds)
    }

    /**
     * Soft-delete library books absent from [seen]; see [BookRepository.softDeleteAbsentByPaths].
     *
     * Accepts the set of folder-qualified [FolderScopedPath] locators seen on disk during a full
     * scan — no BookId resolution required for books that did not change. Folder-qualifying the
     * seen set closes a cross-folder path-aliasing bug (two folders sharing a relative path).
     *
     * Returns the number of books tombstoned, so the orchestrator knows a delete-only full scan
     * changed rows above the client cursor and must broadcast the reconcile nudge.
     */
    suspend fun softDeleteAbsentByPaths(
        libraryId: LibraryId,
        seen: Set<FolderScopedPath>,
    ): Int

    /**
     * Soft-delete the live book at `(folderId, rootRelPath)`, if one exists.
     *
     * Idempotent: a no-op when no live (non-deleted) book exists at that folder-scoped path
     * (already tombstoned or never ingested). Emits
     * [com.calypsan.listenup.api.sync.SyncEvent.Deleted] to the change bus so connected clients
     * reflow immediately.
     *
     * Returns 1 when a live book was tombstoned, 0 on the idempotent no-op — so the orchestrator
     * can count the deletions a delete-only suppressed scan applied and broadcast the reconcile nudge.
     *
     * Called from [com.calypsan.listenup.server.services.BookPersister] when a
     * [com.calypsan.listenup.api.dto.scanner.ChangeEventDto.Removed] arrives on an
     * incremental scan — the only path that explicitly notifies of a deletion without
     * walking the entire library.
     */
    suspend fun softDeleteByPath(
        folderId: FolderId,
        rootRelPath: String,
    ): Int
}

/**
 * A book's identity locator within a scan: the owning [folderId] plus its folder-relative
 * [rootRelPath]. The unit of the folder-scoped tombstone sweep — comparing these pairs (rather than
 * bare paths) keeps two folders that share a relative path from aliasing each other.
 */
data class FolderScopedPath(
    val folderId: FolderId,
    val rootRelPath: String,
)

/**
 * The scan-wide pre-resolved identity maps [BookPersister] threads through every per-book
 * [BookIngestPort.resolveOrInsert] call. [contributors] is keyed by the contributor dedup key,
 * [series] by the series normalized key — exactly the keys [BookRepository.upsertFromAnalyzed]
 * recomputes to look an id up, so a book's contributors/series resolve without a per-call SELECT.
 */
data class ScanIdentityMaps(
    val contributors: Map<String, ContributorId> = emptyMap(),
    val series: Map<String, SeriesId> = emptyMap(),
    /**
     * Pre-resolved genre ids, keyed by the normalized raw genre string (`raw.trim().lowercase()`).
     * Built once across every changed book's distinct raw strings — running the alias → normalize →
     * auto-create cascade per distinct string, pre-creating new genres in the suspend prepare phase.
     * The batched write loop reads a book's genre ids from this map and writes the junctions
     * synchronously inside the chunk transaction, instead of a per-book post-commit pass.
     */
    val genres: Map<String, List<String>> = emptyMap(),
)

/**
 * The outcome of a batched [BookIngestPort.resolveOrInsertAll] pass: how many changed books were
 * [persisted] vs [failed], and the set of [resolvedIds] that landed — the seen-set the orchestrator
 * folds for the full-scan tombstone reconciliation.
 */
data class PersistResult(
    val persisted: Int,
    val failed: Int,
    val resolvedIds: Set<BookId>,
)

/**
 * Thrown by [BookIngestPort.resolveOrInsertAll] when an [OutOfMemoryError] forces an early stop.
 * Wraps the partial [result] accumulated before the OOM so the orchestrator can emit honest
 * [com.calypsan.listenup.api.dto.scanner.ScanResultSummary] counts before rethrowing the underlying
 * [OutOfMemoryError]. OOM signals a compromised heap and must never be swallowed per-book.
 */
class PersistAbortedByOom(
    val result: PersistResult,
    cause: OutOfMemoryError,
) : OutOfMemoryError(cause.message)

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
