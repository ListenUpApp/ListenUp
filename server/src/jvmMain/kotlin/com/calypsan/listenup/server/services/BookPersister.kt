package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.CoverSource
import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CoverSource as SyncCoverSource
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.api.SystemCollectionType
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.cover.PendingCover
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.scanner.CoverSpool
import com.calypsan.listenup.server.scanner.toSummary
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.io.path.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path as JPath

private val log = KotlinLogging.logger {}

/**
 * Cap the number of PERSISTING progress ticks emitted per scan, independent of library size: the
 * persister emits at most this many `ScanEvent.Progress(phase = PERSISTING)` events (plus the
 * initial 0 and a final at the total). Keeps the scan event stream light while the "Saving library"
 * bar still advances smoothly on a large library.
 */
private const val PERSIST_PROGRESS_TICKS = 100

/**
 * A bulk incremental scan with more than this many changed books is firehose-suppressed like a
 * full scan, rather than published per-book to the live tail. Above this, the per-event burst
 * would flood the lossy [com.calypsan.listenup.server.sync.ChangeBus] (replay=256, DROP_OLDEST)
 * and storm connected clients into a per-event transaction GC storm; below it, incrementals stay
 * live so a normal one-or-few-book change is a real-time delta. Kept well under the live tail's
 * 256-deep buffer so a suppressed burst can never overflow it.
 */
private const val INCREMENTAL_FIREHOSE_SUPPRESS_THRESHOLD = 50

/**
 * Consumes the Scanner's [ScanResult] stream and persists every
 * [com.calypsan.listenup.api.dto.scanner.AnalyzedBook] through [BookIngestPort].
 *
 * Per-book error containment: a single malformed book (corrupt audio header,
 * etc.) is logged and counted, never aborting the rest of the scan's books.
 *
 * Tombstone sweep: a [ScanScope.Full] result is authoritative for the whole
 * library, so books absent from it are soft-deleted — but only when every book
 * persisted. If any book failed, the seen-set is incomplete and the sweep is
 * skipped (it would otherwise tombstone a present-but-transiently-failed book);
 * the next clean Full scan reconciles genuine removals. A [ScanScope.Subtree]
 * result only re-walked one book-root — absence there is not authoritative, so
 * no sweep runs.
 *
 * System-collection auto-membership: the persister resolves the library's target system
 * collection ONCE per scan (via [com.calypsan.listenup.server.api.CollectionServiceImpl.getOrCreateSystemCollection])
 * and threads its id into every per-book ingest. The choice is binary and mutually exclusive:
 *   - inbox gate OFF → ALL_BOOKS id — new book is visible to all members immediately.
 *   - inbox gate ON  → INBOX id — new book is quarantined (admin must approve to move it).
 * [BookIngestPort.resolveOrInsert] commits the book→collection membership inside the very
 * same transaction as a genuinely NEW book row, so the firehose — which evaluates
 * [com.calypsan.listenup.server.api.BookAccessPolicy.canAccess] at delivery — never
 * exposes a held book to members (it is already in the admin-only inbox before the
 * `book.Created` publish is observable). This closes the TOCTOU leak that the old
 * separate-transaction `addToInbox` hook carried. Re-scans/updates of an existing
 * book never re-add membership. Resolution failure logs a warning and falls back to
 * null, leaving the scanned books uncollected — invisible to members under the pure-union
 * rule until an admin collects them (a rare ingest fallback that never fails the scan).
 *
 * [coverImageStore] is optional: when non-null, the persister extracts cover
 * bytes from each [AnalyzedBook] (filesystem or embedded) and passes a
 * [PendingCover] to [BookIngestPort.resolveOrInsert] so the repository can
 * write the managed cover file after the book id is known. When null (e.g. in
 * pure orchestration tests), cover extraction is skipped.
 *
 * [libraryRepository] reads the per-library `inboxEnabled` gate; [collectionService]
 * resolves (or creates) the appropriate system collection (ALL_BOOKS or INBOX) each scan.
 * Both back the system-collection auto-membership described above.
 */
class BookPersister internal constructor(
    private val ingest: BookIngestPort,
    private val libraryRegistry: LibraryRegistry,
    private val libraryRepository: LibraryRepository,
    private val collectionService: CollectionServiceImpl,
    private val sql: ListenUpDatabase,
    private val scanResultBus: SharedFlow<ScanResult>,
    private val eventBus: MutableSharedFlow<ScanEvent>,
    private val scope: CoroutineScope,
    private val coverImageStore: CoverImageStore? = null,
    private val coverSpool: CoverSpool? = null,
) {
    /** Launch the collector. Idempotent only in the sense that it should be called once at bootstrap. */
    fun start() {
        scope.launch {
            scanResultBus.collect { result -> persist(result) }
        }
    }

    /** Visible for tests — drive one ScanResult through without the bus. */
    internal suspend fun persist(result: ScanResult) {
        try {
            val libraryId = libraryRegistry.currentLibrary()
            // Resolve the folder whose root_path matches the scan result's rootPath.
            // Falls back to a sentinel folderId so a misconfigured folder doesn't
            // block persistence; the TODO below tracks proper error surfacing.
            val folderId = resolveFolderId(result.rootPath)

            // Suppress the per-book firehose PUBLISH for any BULK persist so the lossy live tail
            // (ChangeBus replay=256, DROP_OLDEST) never carries the burst — otherwise it overflows
            // and storms connected clients into a per-event transaction GC storm. A FULL scan is
            // always bulk (onboarding, re-scan); a large INCREMENTAL (dropping a folder of many
            // books, or a big subtree re-persist) is bulk too. Revisions still bump, so the client
            // does one clean REST catch-up after the Completed below. Small incrementals stay live —
            // they ARE real-time deltas.
            val suppressFirehose =
                result.scope is ScanScope.Full ||
                    result.changes.size > INCREMENTAL_FIREHOSE_SUPPRESS_THRESHOLD
            val counts: PersistCounts
            try {
                counts =
                    if (suppressFirehose) {
                        withContext(FirehoseSuppressed) { persistAll(result, libraryId, folderId) }
                    } else {
                        persistAll(result, libraryId, folderId)
                    }
            } catch (e: OutOfMemoryError) {
                // OOM means the JVM heap is compromised. We still emit Completed with the partial
                // counts gathered so far (stored in the thrown PersistAbortedByOom) so clients get
                // honest numbers, then rethrow so the process can surface the failure.
                val partial =
                    (e as? PersistAbortedByOom)?.result?.let { PersistCounts(it.persisted, it.failed) }
                        ?: PersistCounts(0, 0)
                eventBus.emit(ScanEvent.Completed(result.correlationId, libraryId, result.toSummary(partial)))
                throw e
            }

            // Completed is emitted HERE — after every book is committed and (for a full scan) the
            // tombstone sweep has run — not by the Scanner before this persist runs. `Completed` must
            // mean "the library is persisted and queryable", so the client reconciles a settled server
            // exactly once instead of racing a still-writing one (the premature-Completed bug).
            eventBus.emit(ScanEvent.Completed(result.correlationId, libraryId, result.toSummary(counts)))
        } finally {
            coverSpool?.clearScan(result.correlationId)
        }
    }

    /**
     * Persists only the **changed** books from [result] and, for a [ScanScope.Full] result,
     * runs the tombstone sweep. The caller decides whether this runs under [FirehoseSuppressed];
     * this method is suppression-agnostic.
     *
     * Unchanged books (present in [ScanResult.books] but absent from [ScanResult.changes]) incur
     * ZERO per-book work: no cover read, no DB lookup, no contributor/series resolution. The Differ
     * already computed the delta; this method drives persistence from that delta. Added/Modified/Moved
     * each carry their [AnalyzedBook] directly, so no secondary lookup against [ScanResult.books] is
     * needed; Removed books are tombstoned immediately so incremental deletions reflow without waiting
     * for a Full scan.
     *
     * Returns [PersistCounts] summarising how many changed books landed vs failed, so the caller can
     * stamp honest numbers into [ScanEvent.Completed]. On [OutOfMemoryError] the loop is stopped
     * immediately and a [PersistAbortedByOom] wraps both the partial counts and the original error —
     * OOM signals a compromised heap and must never be swallowed per-book.
     *
     * Tombstone sweep safety: [ScanResult.books] represents every book present on disk at scan
     * time, including books that changed but failed to persist. A failed book is still on disk,
     * so its `rootRelPath` IS in [seenPaths] — the path-based sweep will not tombstone it. The
     * sweep is therefore safe regardless of persist failures; no skip-on-failure guard is needed.
     */
    private suspend fun persistAll(
        result: ScanResult,
        libraryId: LibraryId,
        folderId: FolderId,
    ): PersistCounts {
        // Build the seen-paths set cheaply from the scan result — no DB, no per-book work.
        // This represents every rootRelPath present on disk at scan time.
        val seenPaths = result.books.mapTo(mutableSetOf()) { it.candidate.rootRelPath }

        // The books carried by `result.changes` are artwork-FREE: the Scanner strips Embedded/Spooled
        // covers off the diff copies (AnalyzedBook.withoutArtwork) so the volatile artwork bytes and
        // the per-scan spool path never pollute change detection. The cover-bearing copies live in
        // `result.books`. Persist must read each changed book's cover from there, or every embedded
        // cover lands as a null cover_source. Keyed by rootRelPath — stable across Added/Modified/Moved.
        val coverBearingByPath = result.books.associateBy { it.candidate.rootRelPath }

        fun coverBearing(change: AnalyzedBook): AnalyzedBook =
            coverBearingByPath[change.candidate.rootRelPath] ?: change

        // Use the scan result's rootPath for filesystem cover reads — aligned
        // with Analyzer's own path resolution (Analyzer.kt: rootPath.resolve(relPath)).
        val scanRoot = JPath.of(result.rootPath)
        // Resolve the library's system collection ONCE per scan: ALL_BOOKS when the inbox gate
        // is off (non-held), INBOX when it is on (held). The two cases are mutually exclusive —
        // a held book must never join ALL_BOOKS or it becomes visible to all members. A resolution
        // failure must never fail the scan — fall back to null (book lands uncollected →
        // invisible to members under pure-union until an admin collects it).
        val systemCollectionId = resolveSystemCollectionId(libraryId)
        var persisted = 0
        var failed = 0

        // Persistence is a visible phase, not a silent gap. The bar binds to booksAnalyzed/booksTotal
        // and hits 100% the instant ANALYZING ends; without these events the client would freeze at
        // 100% for the whole persist. We emit PERSISTING progress so the same bar advances 0→100% again
        // under a "Saving library" label. [toPersist] is the count of books actually written
        // (Added/Modified/Moved); Removed changes are tombstones, not persisted books.
        val changedBooks =
            result.changes.mapNotNull { change ->
                when (change) {
                    is ChangeEventDto.Added -> coverBearing(change.book)
                    is ChangeEventDto.Modified -> coverBearing(change.book)
                    is ChangeEventDto.Moved -> coverBearing(change.book)
                    is ChangeEventDto.Removed -> null
                }
            }
        val toPersist = changedBooks.size

        // Resolve every contributor, series, AND distinct genre string across the whole scan to a
        // stable id ONCE, before the write loop — collapsing the per-book resolveOrCreate transaction
        // storm (a SELECT, plus a create txn per new name, per contributor/series/genre per book) into
        // one bulk resolve per catalogue. The maps thread into the batched write so a book's
        // contributors/series/genres resolve from memory, not the database. Brand-new names still
        // create + emit identically.
        val identityMaps = ingest.resolveScanIdentities(changedBooks)

        // Extract every changed book's cover bytes OFF the write transaction (filesystem/embedded
        // reads must not run inside a SQLDelight transaction), keyed by rootRelPath. The batched write
        // path stores each cover file after the book id is known, exactly as the per-book path did.
        val coversByBook =
            buildMap {
                for (book in changedBooks) {
                    extractPendingCover(book, scanRoot)?.let { put(book.candidate.rootRelPath, it) }
                }
            }

        val persistProgressStride = maxOf(1, toPersist / PERSIST_PROGRESS_TICKS)
        var lastTickAt = -1

        suspend fun emitPersistProgress(processed: Int) {
            eventBus.emit(
                ScanEvent.Progress(
                    correlationId = result.correlationId,
                    libraryId = libraryId,
                    phase = ScanPhase.PERSISTING,
                    filesWalked = 0,
                    booksAnalyzed = processed,
                    errors = failed,
                    booksTotal = toPersist,
                ),
            )
        }

        // Initial tick at 0 so the UI leaves the 100%-analyze state the moment persistence begins.
        if (toPersist > 0) emitPersistProgress(0)

        // Batched persist: identity/genre/cover resolution + bulk existence in a suspend prepare phase,
        // then chunked write transactions (O(chunks), not O(books)). The progress callback fires per
        // chunk; we throttle emission to ~PERSIST_PROGRESS_TICKS ticks for a large library.
        val persistResult =
            ingest.resolveOrInsertAll(
                libraryId = libraryId,
                folderId = folderId,
                books = changedBooks,
                coversByBook = coversByBook,
                systemCollectionId = systemCollectionId,
                identityMaps = identityMaps,
            ) { processed, failedSoFar ->
                failed = failedSoFar
                if (processed - lastTickAt >= persistProgressStride || processed == toPersist) {
                    lastTickAt = processed
                    emitPersistProgress(processed)
                }
            }
        persisted = persistResult.persisted
        failed = persistResult.failed

        // Incremental Removed changes tombstone the book at their path immediately so deletions reflow
        // without waiting for the next Full scan. Idempotent: a no-op when the book is already deleted
        // or never existed. For Full scans the softDeleteAbsentByPaths sweep below would catch it too,
        // so the overlap is harmless.
        for (change in result.changes) {
            if (change is ChangeEventDto.Removed) {
                try {
                    ingest.softDeleteByPath(libraryId, change.rootRelPath)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.warn(e) { "softDeleteByPath failed for ${change.rootRelPath} — continuing" }
                }
            }
        }

        // Final tick at the total so the bar lands on 100% before the terminal Completed, even if the
        // last chunk boundary didn't fall exactly on the final book.
        if (toPersist > 0 && lastTickAt != toPersist) emitPersistProgress(toPersist)

        if (result.scope is ScanScope.Full) {
            // Path-based sweep is safe: every book present on disk (including any that failed
            // to persist) is in seenPaths, so the sweep never tombstones a present book.
            ingest.softDeleteAbsentByPaths(libraryId, seenPaths)
        }
        return PersistCounts(persisted, failed)
    }

    /**
     * Resolves the library's system collection id for a scan pass:
     *
     *  - inbox gate OFF (not held) → ALL_BOOKS collection id — new books are immediately
     *    visible to members (every member holds an ALL_BOOKS grant).
     *  - inbox gate ON  (held)     → INBOX collection id — new books are quarantined and
     *    hidden from members until an admin approves them (moves INBOX → ALL_BOOKS).
     *
     * The two outcomes are **mutually exclusive**: a held book must NOT join ALL_BOOKS (it
     * would be visible to all members, defeating the inbox quarantine); a non-held book
     * must NOT join INBOX (it would be quarantined unnecessarily).
     *
     * Resolution failure (typed [AppResult.Failure] or escaped DB fault) must not fail the
     * scan — it logs a warning and returns null, leaving the new book uncollected. Under the
     * pure-union rule an uncollected book is invisible to members until an admin adds it to a
     * collection (it is never silently public). [CancellationException] is always re-raised.
     */
    private suspend fun resolveSystemCollectionId(libraryId: LibraryId): String? =
        try {
            val type =
                if (libraryRepository.readInboxEnabled(libraryId)) {
                    SystemCollectionType.INBOX
                } else {
                    SystemCollectionType.ALL_BOOKS
                }
            when (val result = collectionService.getOrCreateSystemCollection(libraryId.value, type)) {
                is AppResult.Success -> {
                    result.data.id.value
                }

                is AppResult.Failure -> {
                    log.warn {
                        "System collection (${type.name}) resolution skipped for library ${libraryId.value}: ${result.error.code}"
                    }
                    null
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) {
                "System collection resolution failed for library ${libraryId.value}; scanning without collection membership"
            }
            null
        }

    /**
     * Reads cover bytes from [analyzed] when [coverImageStore] is configured.
     * Returns null when cover storage is unconfigured, the book has no cover, or
     * the filesystem cover file cannot be read.
     *
     * For [CoverSource.Filesystem]: reads the image bytes from [scanRoot]/[file.relPath]
     * using `kotlin.io.path.readBytes()` — consistent with the project's file-handling
     * conventions; java.io.* is the JVM-only boundary this module already depends on.
     * For [CoverSource.Embedded]: uses the artwork bytes already in memory.
     *
     * This is intentionally done outside the DB transaction — file I/O does not belong
     * inside an Exposed `suspendTransaction`.
     */
    private fun extractPendingCover(
        analyzed: AnalyzedBook,
        scanRoot: JPath,
    ): PendingCover? {
        if (coverImageStore == null) return null
        return when (val cover = analyzed.cover) {
            null -> {
                null
            }

            is CoverSource.Filesystem -> {
                val coverPath = scanRoot.resolve(cover.file.relPath)
                runCatching { coverPath.readBytes() }
                    .onFailure { e ->
                        log.warn { "Could not read filesystem cover for ${analyzed.candidate.rootRelPath}: $e" }
                    }.getOrNull()
                    ?.let { bytes ->
                        PendingCover(bytes = bytes, mime = "image/jpeg", source = SyncCoverSource.FILESYSTEM)
                    }
            }

            is CoverSource.Embedded -> {
                PendingCover(
                    bytes = cover.artwork.bytes,
                    mime = cover.artwork.mime,
                    source = SyncCoverSource.EMBEDDED,
                )
            }

            is CoverSource.Spooled -> {
                runCatching { JPath.of(cover.path).readBytes() }
                    .onFailure { e ->
                        log.warn { "Could not read spooled cover for ${analyzed.candidate.rootRelPath}: $e" }
                    }.getOrNull()
                    ?.let { bytes ->
                        PendingCover(bytes = bytes, mime = cover.mime, source = SyncCoverSource.EMBEDDED)
                    }
            }
        }
    }

    /**
     * Looks up the [FolderId] for the folder whose [LibraryFolderTable.rootPath]
     * matches [rootPath]. Returns a sentinel when no matching folder is found —
     * the caller logs the miss and continues rather than failing the whole scan.
     *
     * TODO: surface a typed error when the folder row is missing (LIB-D / ScanOrchestrator).
     */
    private suspend fun resolveFolderId(rootPath: String): FolderId =
        suspendTransaction(sql) {
            sql.libraryFoldersQueries
                .selectLiveByRootPath(root_path = rootPath)
                .executeAsOneOrNull()
                ?.let { FolderId(it.id) }
        } ?: run {
            log.warn { "No library_folder row found for rootPath='$rootPath' — book folderId will be unknown" }
            FolderId("unknown")
        }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/** Counts of books successfully persisted vs failed during [BookPersister.persistAll]. */
internal data class PersistCounts(
    val persisted: Int,
    val failed: Int,
)

/**
 * Builds a [com.calypsan.listenup.api.dto.scanner.ScanResultSummary] from this result, stamping
 * in the [PersistCounts] that [BookPersister.persistAll] gathered. This is the persister's own
 * overload — [com.calypsan.listenup.server.scanner.toSummary] is used by the Scanner before
 * persistence counts are known.
 */
internal fun ScanResult.toSummary(counts: PersistCounts): com.calypsan.listenup.api.dto.scanner.ScanResultSummary =
    toSummary().copy(persisted = counts.persisted, failed = counts.failed)
