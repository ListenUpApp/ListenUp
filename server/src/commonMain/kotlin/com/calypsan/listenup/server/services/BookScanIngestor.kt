package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.cover.ManagedCoverFiles
import com.calypsan.listenup.server.cover.PendingCover
import com.calypsan.listenup.server.cover.StoredCoverInfo
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.TransactionLocal
import com.calypsan.listenup.server.db.sqldelight.setTransactionLocal
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

private val log = loggerFor<BookScanIngestor>()

/**
 * Books per chunked write batch in [BookScanIngestor.resolveOrInsertAll]. Each book in a chunk
 * opens its own [suspendTransaction] for per-book error containment while progress ticks, tag writes,
 * and revival cascades are batched per chunk.
 */
private const val PERSIST_CHUNK_SIZE = 250

/** SQLite caps a statement at 999 bound parameters; chunk `IN (…)` lists below it with headroom. */
private const val SQLITE_IN_CHUNK = 900

/**
 * A book resolved + prepared by [BookScanIngestor.resolveOrInsertAll]'s suspend prepare phase, ready
 * for the chunked write loop. [payload] is the built aggregate; [extras] carries the pre-resolved
 * managed cover, system-collection id (on genuine insert), and genre ids the synchronous
 * `writePayload` reads via the transaction-local; [skip] marks an idempotent re-scan whose content +
 * cover + genres are unchanged (no write, no revision bump, no emit).
 */
private data class PreparedBook(
    val bookId: BookId,
    val payload: BookSyncPayload,
    val skip: Boolean,
    val tags: List<String>,
    val extras: BookWriteExtras,
    /**
     * The `deleted_at` of the stored row this write is reviving, or null when the book is genuinely
     * new or already live. Non-null only when re-ingesting a TOMBSTONED book — the write clears its
     * `deleted_at`, so its cascade-tombstoned junctions must be revived (floored at this value).
     */
    val revivedFromDeletedAt: Long?,
)

/**
 * The output of [BookScanIngestor]'s prepare phase: the [prepared] books ready for the chunked write
 * loop, plus [prepareFailed] — books rejected during prepare (an absolute rootRelPath) that the write
 * loop must count as failed up front.
 */
private class PreparedBatch(
    val prepared: List<PreparedBook>,
    val prepareFailed: Int,
) {
    operator fun component1() = prepared

    operator fun component2() = prepareFailed
}

/** Evaluated rescan inputs shared between [BookScanIngestor.prepareBooks] and [BookScanIngestor.upsertFromAnalyzed]. */
private data class PreparedRescanDecision(
    val effectivePayload: BookSyncPayload,
    val genreIds: List<String>?,
    val skip: Boolean,
    val preserveContributors: Boolean,
    val preserveSeries: Boolean,
)

/**
 * Scanner ingest coordinator for [BookRepository].
 *
 * Owns single-book ([resolveOrInsert], [upsertFromAnalyzed]) and batched ([resolveScanIdentities],
 * [resolveOrInsertAll]) scan persistence: natural-key + move identity resolution, per-field
 * provenance merge, idempotent-rescan skip, off-transaction cover storage, chunked per-book
 * write transactions, and post-commit tag/revival cascades.
 */
internal class BookScanIngestor(
    private val db: ListenUpDatabase,
    private val sync: BookSyncBridge,
    private val analyzedBookMapper: AnalyzedBookMapper,
    private val contributorRepository: ContributorRepository,
    private val seriesRepository: SeriesRepository,
    private val bookFinder: BookFinder,
    private val bookGenreWriter: BookGenreWriter,
    private val bookTagWriter: BookTagWriter?,
    private val managedCoverFiles: ManagedCoverFiles,
    private val locationManager: BookLocationManager,
    private val lifecycle: BookLifecycle,
) {
    /** Resolves an [AnalyzedBook] to a stable [BookId] and writes its aggregate. */
    suspend fun resolveOrInsert(
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover?,
        systemCollectionId: String?,
        contributorIds: Map<String, ContributorId>?,
        seriesIds: Map<String, SeriesId>?,
    ): AppResult<IngestOutcome> {
        val rootRelPath = analyzed.candidate.rootRelPath
        // Defense-in-depth: the natural key is library-relative. An absolute path here means an
        // upstream scanner bug leaked one; persisting it would miss findByPath on every rescan and
        // mass-trip the inode "moved" branch. Fail loud and contained.
        require(!rootRelPath.startsWith("/")) {
            "rootRelPath must be library-relative, got absolute: $rootRelPath"
        }

        // The three identity branches differ only in the resolved BookId and the wasNew flag; the
        // aggregate write (and its threaded pre-resolved id maps) is identical, so close over it once.
        suspend fun write(bookId: BookId): AppResult<BookSyncPayload> =
            upsertFromAnalyzed(
                bookId = bookId,
                libraryId = libraryId,
                folderId = folderId,
                analyzed = analyzed,
                pendingCover = pendingCover,
                systemCollectionId = systemCollectionId,
                contributorIds = contributorIds,
                seriesIds = seriesIds,
            )

        bookFinder.findByPath(folderId, rootRelPath)?.let { existing ->
            return write(existing).map { IngestOutcome(existing, wasNew = false) }
        }

        // The move hint is folder-scoped `(folder_id, inode)` — identity is anchored to the
        // owning folder. Intra-folder moves preserve UUID; cross-folder moves mint a fresh id.
        val movedBookId = locationManager.findTrustedMove(folderId, analyzed)
        if (movedBookId != null) {
            val previousPath = sync.findById(movedBookId)?.rootRelPath
            log.info { "Book moved: ${previousPath ?: "(unknown)"} → $rootRelPath" }
            return write(movedBookId).map { IngestOutcome(movedBookId, wasNew = false) }
        }

        val newId = BookId(Uuid.random().toString())
        return write(newId).map { IngestOutcome(newId, wasNew = true) }
    }

    /**
     * Batch-resolves every contributor, series, and distinct genre string across [books] to a stable
     * id in bulk lookups, run ONCE before the persist loop.
     */
    suspend fun resolveScanIdentities(books: Collection<AnalyzedBook>): ScanIdentityMaps {
        val contributorIdentities =
            books.flatMap { analyzedBookMapper.buildContributors(it) }.map { it.name to it.sortName }
        val seriesNames = books.flatMap { analyzedBookMapper.buildSeries(it) }.map { it.name }
        return ScanIdentityMaps(
            contributors = contributorRepository.resolveOrCreateAll(contributorIdentities),
            series = seriesRepository.resolveOrCreateAll(seriesNames),
            genres = resolveScanGenres(books),
        )
    }

    /** Batch-persists every changed book in [books] in chunked per-book write transactions. */
    suspend fun resolveOrInsertAll(
        libraryId: LibraryId,
        folderId: FolderId,
        books: List<AnalyzedBook>,
        coversByBook: Map<String, PendingCover>,
        systemCollectionId: String?,
        identityMaps: ScanIdentityMaps,
        onProgress: suspend (processed: Int, failed: Int) -> Unit,
    ): PersistResult {
        val (prepared, prepareFailed) =
            prepareBooks(
                libraryId = libraryId,
                folderId = folderId,
                books = books,
                coversByBook = coversByBook,
                systemCollectionId = systemCollectionId,
                identityMaps = identityMaps,
            )

        // Suppression is read ONCE in the suspend context and threaded into every write — the
        // synchronous chunk body cannot read the coroutine context.
        val suppressed = currentCoroutineContext()[FirehoseSuppressed.Key] != null

        var persisted = 0
        // Books rejected in the prepare phase (e.g. an absolute rootRelPath) are already counted failed.
        var failed = prepareFailed
        val resolvedIds = mutableSetOf<BookId>()
        val writtenRootRelPaths = mutableSetOf<String>()

        for (chunk in prepared.chunked(PERSIST_CHUNK_SIZE)) {
            // OOM mid-chunk wraps the partial counts: prior chunks (persisted/failed/resolvedIds) plus
            // this chunk's already-committed successes and the books that failed up to the OOM point.
            val succeeded =
                writeChunk(chunk, suppressed) { chunkSucceeded, chunkFailed ->
                    PersistResult(
                        persisted = persisted + chunkSucceeded.size,
                        failed = failed + chunkFailed,
                        resolvedIds = (resolvedIds + chunkSucceeded.map { it.bookId }).toSet(),
                    )
                }
            persisted += succeeded.size
            failed += chunk.size - succeeded.size
            succeeded.forEach { book ->
                resolvedIds += book.bookId
                writtenRootRelPaths += book.payload.rootRelPath
            }

            // Scan tags reconcile AFTER the chunk's book rows commit, as a suspend post-pass — matching
            // the per-book path's unconditional post-success writeScanTags.
            bookTagWriter?.let { writer ->
                for (book in succeeded) {
                    if (book.tags.isNotEmpty()) writer.writeScanTags(book.bookId, book.tags)
                }
            }

            // Scan revival cascade: any book in this chunk that revived a tombstoned row must get
            // its cascade-tombstoned junctions revived too, floored at each book's own deleted_at.
            succeeded
                .mapNotNull { book -> book.revivedFromDeletedAt?.let { floor -> floor to book.bookId.value } }
                .groupBy({ it.first }, { it.second })
                .forEach { (floor, ids) -> lifecycle.reviveBookJunctions(ids, floor) }

            onProgress(persisted + failed, failed)
        }
        return PersistResult(
            persisted = persisted,
            failed = failed,
            resolvedIds = resolvedIds,
            // Every book not written, whichever phase dropped it, so the next scan offers it again.
            failedRootRelPaths = books.mapTo(mutableSetOf()) { it.candidate.rootRelPath } - writtenRootRelPaths,
        )
    }

    /**
     * Builds a [BookSyncPayload] from [analyzed] under [bookId] and writes the full aggregate
     * through [BookSyncBridge.upsert], with per-field provenance merge and idempotent-rescan skip.
     */
    suspend fun upsertFromAnalyzed(
        bookId: BookId,
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover? = null,
        systemCollectionId: String? = null,
        contributorIds: Map<String, ContributorId>? = null,
        seriesIds: Map<String, SeriesId>? = null,
    ): AppResult<BookSyncPayload> {
        val payload =
            buildPayloadFromAnalyzed(
                bookId = bookId,
                libraryId = libraryId,
                folderId = folderId,
                analyzed = analyzed,
                contributorIds = contributorIds,
                seriesIds = seriesIds,
            )
        // Read the existing aggregate ONCE — drives the idempotency check, the cover-source
        // sticky-UPLOADED skip, and the only-on-create system-collection membership gate.
        val existing = sync.findById(bookId)
        val isNew = existing == null
        val decision =
            evaluateRescan(
                payload = payload,
                existing = existing,
                analyzed = analyzed,
                pendingCover = pendingCover,
                resolveGenres = {
                    val distinctRaws = analyzed.genres.filter { it.isNotBlank() }.distinctBy { it.trim().lowercase() }
                    distinctRaws.flatMap { raw -> bookGenreWriter.resolveGenreIds(raw) }.distinct()
                },
                readStoredGenreIds = { storedGenreIds(listOf(bookId.value))[bookId.value].orEmpty() },
            )
        val result: AppResult<BookSyncPayload> =
            if (decision.skip && existing != null) {
                log.debug { "upsertFromAnalyzed: idempotent re-scan for ${bookId.value}, skipping revision bump" }
                AppResult.Success(existing)
            } else {
                // File I/O must stay OUTSIDE the DB transaction — store the cover first, then upsert.
                val storedCover = storeCoverUnlessUploaded(bookId, existing, pendingCover)
                val extras =
                    buildExtras(
                        storedCover = storedCover,
                        systemCollectionId = if (isNew) systemCollectionId else null,
                        decision = decision,
                    )
                withContext(TransactionLocal(extras)) {
                    sync.upsert(
                        decision.effectivePayload.copy(
                            cover = scanCoverForWrite(analyzed, storedCover, existing),
                        ),
                        clientOpId = null,
                    )
                }
            }
        if (result is AppResult.Success) {
            existing?.deletedAt?.let { floor -> lifecycle.reviveBookJunctions(listOf(bookId.value), floor) }
            bookTagWriter?.writeScanTags(bookId, analyzed.tags)
        }
        return result
    }

    /**
     * Writes one chunk of [PreparedBook]s, each book in its OWN [suspendTransaction] — genuine per-book
     * error containment. Returns the books that committed.
     */
    private suspend fun writeChunk(
        chunk: List<PreparedBook>,
        suppressed: Boolean,
        oomPartial: (chunkSucceeded: List<PreparedBook>, chunkFailed: Int) -> PersistResult,
    ): List<PreparedBook> {
        val succeeded = mutableListOf<PreparedBook>()
        var failedInChunk = 0
        for (book in chunk) {
            try {
                // A skipped book's content, cover AND genre set all match what is stored, so it
                // writes nothing — genres ride the same revision bump as every other change.
                if (!book.skip) {
                    suspendTransaction<Unit>(db) {
                        setTransactionLocal(book.extras)
                        try {
                            with(sync) { upsertBookInOpenTransaction(book.payload, suppressed) }
                        } finally {
                            setTransactionLocal(null)
                        }
                    }
                }
                succeeded += book
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                failedInChunk++
                throw PersistAbortedByOom(oomPartial(succeeded.toList(), failedInChunk), e)
            } catch (e: Throwable) {
                failedInChunk++
                log.warn(e) { "Book persist threw: ${book.payload.rootRelPath} — continuing" }
            }
        }
        return succeeded
    }

    /**
     * The PREPARE half of [resolveOrInsertAll] (suspend, no write transaction): resolves identity,
     * builds the payload, resolves genre ids, stores the cover file, and computes the idempotency-skip
     * + system-membership-on-insert flags for every book in [books].
     */
    private suspend fun prepareBooks(
        libraryId: LibraryId,
        folderId: FolderId,
        books: List<AnalyzedBook>,
        coversByBook: Map<String, PendingCover>,
        systemCollectionId: String?,
        identityMaps: ScanIdentityMaps,
    ): PreparedBatch {
        // Reject absolute paths up front, contained per book: an absolute rootRelPath is an upstream
        // scanner bug, so the book is counted as failed and dropped — never aborting the batch.
        val (valid, invalid) = books.partition { !it.candidate.rootRelPath.startsWith("/") }
        for (book in invalid) {
            log.warn { "rootRelPath must be library-relative, got absolute: ${book.candidate.rootRelPath} — skipping" }
        }

        // Bulk existence: one IN-read for natural keys, one for inode candidates.
        val byPath = bookFinder.findExistingByPaths(folderId, valid.map { it.candidate.rootRelPath })
        val pathsInThisIngest = valid.mapTo(mutableSetOf()) { it.candidate.rootRelPath }
        val inodeCandidates =
            bookFinder.findInodeCandidates(
                folderId,
                valid.mapNotNull { it.candidate.identityInode() },
            )
        // Read once per batch, and only when an inode matched something that needs the disk's word.
        val folderRoot = if (inodeCandidates.isEmpty()) null else locationManager.folderRootPath(folderId)
        val occupancy = MoveOccupancy(pathsInThisIngest, folderRoot)

        data class Resolved(
            val analyzed: AnalyzedBook,
            val bookId: BookId,
            val isNew: Boolean,
        )

        val resolved =
            valid.map { analyzed ->
                val rootRelPath = analyzed.candidate.rootRelPath
                val identityFile = analyzed.candidate.identityFile()
                val candidates = identityFile?.inode?.let { inodeCandidates[it] }
                val movedFrom =
                    if (identityFile != null && candidates != null) {
                        locationManager.trustedMoveOnDisk(candidates, identityFile.size, occupancy)
                    } else {
                        null
                    }
                val existing = byPath[rootRelPath] ?: movedFrom
                Resolved(analyzed, existing ?: BookId(Uuid.random().toString()), isNew = existing == null)
            }

        // One batched read of the existing aggregates — drives the idempotency + cover-sticky checks.
        val existingIds = resolved.filterNot { it.isNew }.map { it.bookId.value }
        val existingById = sync.findAllByIds(existingIds).associateBy { it.id }
        val storedGenreIdsById = storedGenreIds(existingIds)

        val prepared =
            resolved.map { (analyzed, bookId, isNew) ->
                val pendingCover = coversByBook[analyzed.candidate.rootRelPath]
                val payload =
                    buildPayloadFromAnalyzed(
                        bookId = bookId,
                        libraryId = libraryId,
                        folderId = folderId,
                        analyzed = analyzed,
                        contributorIds = identityMaps.contributors,
                        seriesIds = identityMaps.series,
                    )
                val existing = existingById[bookId.value]
                val decision =
                    evaluateRescan(
                        payload = payload,
                        existing = existing,
                        analyzed = analyzed,
                        pendingCover = pendingCover,
                        resolveGenres = { resolveBookGenreIds(analyzed, identityMaps.genres) },
                        readStoredGenreIds = { storedGenreIdsById[bookId.value].orEmpty() },
                    )
                val storedCover =
                    if (decision.skip) {
                        null
                    } else {
                        storeCoverUnlessUploaded(bookId, existing, pendingCover)
                    }
                PreparedBook(
                    bookId = bookId,
                    payload =
                        decision.effectivePayload.copy(
                            cover = scanCoverForWrite(analyzed, storedCover, existing),
                        ),
                    skip = decision.skip,
                    tags = analyzed.tags,
                    extras =
                        buildExtras(
                            storedCover = storedCover,
                            systemCollectionId = if (isNew) systemCollectionId else null,
                            decision = decision,
                        ),
                    // Non-null only when reviving a tombstoned row (skip is always false in that case).
                    revivedFromDeletedAt = existing?.deletedAt,
                )
            }
        return PreparedBatch(prepared = prepared, prepareFailed = invalid.size)
    }

    /**
     * Merges provenance, applies sidecar curation, resolves genres, and decides whether a rescan is
     * an idempotent no-op for both [prepareBooks] and [upsertFromAnalyzed].
     */
    private inline fun evaluateRescan(
        payload: BookSyncPayload,
        existing: BookSyncPayload?,
        analyzed: AnalyzedBook,
        pendingCover: PendingCover?,
        resolveGenres: () -> List<String>,
        readStoredGenreIds: () -> Set<String>,
    ): PreparedRescanDecision {
        val merge = existing?.let { mergeByProvenance(incoming = payload, existing = it) }
        val effectivePayload = (merge?.payload ?: payload).withSidecarCuration(analyzed.sidecarCuration)
        // Null when GENRES is user-protected: the curated set is neither written nor compared.
        val genreIds = if (merge?.preserveGenres == true) null else resolveGenres()
        val genresUnchanged = genreIds == null || genreIds.toSet() == readStoredGenreIds()
        val pendingCoverHash = pendingCover?.run { bytes.sha256Hex() }
        val coverUnavailable = analyzed.cover != null && pendingCover == null
        val coverUnchanged =
            existing?.cover?.source == CoverSource.UPLOADED ||
                coverUnavailable ||
                pendingCoverHash == existing?.cover?.hash
        // Revival guardrail: a byte-identical re-add over a TOMBSTONED book must never skip.
        val skip =
            existing != null &&
                existing.deletedAt == null &&
                coverUnchanged &&
                genresUnchanged &&
                effectivePayload.matchesStoredContent(existing)
        return PreparedRescanDecision(
            effectivePayload = effectivePayload,
            genreIds = genreIds,
            skip = skip,
            preserveContributors = merge?.preserveContributors == true,
            preserveSeries = merge?.preserveSeries == true,
        )
    }

    private suspend fun storeCoverUnlessUploaded(
        bookId: BookId,
        existing: BookSyncPayload?,
        pendingCover: PendingCover?,
    ): StoredCoverInfo? =
        if (existing?.cover?.source == CoverSource.UPLOADED) {
            null
        } else {
            managedCoverFiles.storeCoverIfPresent(bookId, pendingCover)
        }

    private fun buildExtras(
        storedCover: StoredCoverInfo?,
        systemCollectionId: String?,
        decision: PreparedRescanDecision,
    ): BookWriteExtras =
        BookWriteExtras(
            managedCover = storedCover,
            systemCollectionId = systemCollectionId,
            genreIds = decision.genreIds,
            preserveContributors = decision.preserveContributors,
            preserveSeries = decision.preserveSeries,
        )

    private suspend fun buildPayloadFromAnalyzed(
        bookId: BookId,
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        contributorIds: Map<String, ContributorId>?,
        seriesIds: Map<String, SeriesId>?,
    ): BookSyncPayload {
        val resolvedContributors =
            analyzedBookMapper.buildContributors(analyzed).map { c ->
                val id =
                    contributorIds?.get(contributorDedupKey(c.name, c.sortName))?.value
                        ?: contributorRepository.resolveOrCreate(c.name, c.sortName).value
                c.copy(id = id)
            }
        val resolvedSeries =
            analyzedBookMapper.buildSeries(analyzed).map { s ->
                val id =
                    seriesIds?.get(normalizeForDedup(s.name))?.value
                        ?: seriesRepository.resolveOrCreate(s.name).value
                s.copy(id = id)
            }
        return analyzedBookMapper.toBookSyncPayload(
            bookId = bookId,
            libraryId = libraryId,
            folderId = folderId,
            analyzed = analyzed,
            resolvedContributors = resolvedContributors,
            resolvedSeries = resolvedSeries,
        )
    }

    private suspend fun resolveScanGenres(books: Collection<AnalyzedBook>): Map<String, List<String>> {
        val distinctRaw =
            books
                .flatMap { it.genres }
                .filter { it.isNotBlank() }
                .associateBy { it.trim().lowercase() }
        return distinctRaw.mapValues { (_, raw) -> bookGenreWriter.resolveGenreIds(raw) }
    }

    private fun resolveBookGenreIds(
        analyzed: AnalyzedBook,
        genres: Map<String, List<String>>,
    ): List<String> =
        analyzed.genres
            .asSequence()
            .filter { it.isNotBlank() }
            .distinctBy { it.trim().lowercase() }
            .flatMap { raw -> genres[raw.trim().lowercase()].orEmpty() }
            .distinct()
            .toList()

    private suspend fun storedGenreIds(bookIds: List<String>): Map<String, Set<String>> =
        suspendTransaction(db) {
            bookIds
                .chunked(SQLITE_IN_CHUNK)
                .flatMap { chunk -> db.bookGenresQueries.genreIdsByBookIds(chunk).executeAsList() }
                .groupBy({ it.book_id }, { it.genre_id })
                .mapValues { (_, ids) -> ids.toSet() }
        }
}
