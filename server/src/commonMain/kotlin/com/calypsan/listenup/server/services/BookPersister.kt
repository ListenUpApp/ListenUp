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
import com.calypsan.listenup.api.sync.SyncControl
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
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.FirehoseSuppressed
import com.calypsan.listenup.server.io.readBytes
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Clock

private val log = loggerFor<BookPersister>()

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
 * Sentinel [FolderId] a walked root resolves to when no live `library_folders` row registers it
 * (folder soft-deleted mid-scan, or a stale bundle path). It must never drive identity or the
 * tombstone sweep — [BookPersister] skips the Full-scan sweep when any root resolves to it.
 */
private val UNKNOWN_FOLDER_ID = FolderId("unknown")

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
    private val changeBus: ChangeBus,
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
                        withContext(FirehoseSuppressed) { persistAll(result, libraryId) }
                    } else {
                        persistAll(result, libraryId)
                    }
            } catch (e: OutOfMemoryError) {
                // OOM means the JVM heap is compromised. We still emit Completed with the partial
                // counts gathered so far (stored in the thrown PersistAbortedByOom) so clients get
                // honest numbers, then rethrow so the process can surface the failure.
                // OOM aborts before any removal path runs, so removed = 0 on the partial counts. A
                // dropped delete-nudge here self-heals on the next lifecycle edge (books are cursored).
                val partial =
                    (e as? PersistAbortedByOom)?.result?.let { PersistCounts(it.persisted, it.failed, removed = 0) }
                        ?: PersistCounts(0, 0, 0)
                eventBus.emit(ScanEvent.Completed(result.correlationId, libraryId, result.toSummary(partial)))
                throw e
            }

            // Stamp the library's first-ever scan completion (first-only via the IS NULL guard) BEFORE
            // the clean Completed emit — never on the OOM/aborted path above. This is the
            // server-authoritative signal the client's initial-population gate reads: once set, a rescan
            // of the populated library never re-shows the "Building your library" screen.
            libraryRepository.markInitialScanCompleted(libraryId, Clock.System.now().toEpochMilliseconds())

            // A suppressed bulk persist wrote its rows ABOVE the client cursor without publishing to
            // the lossy live tail, so connected clients have no live signal for them — the changed books
            // would surface only after an app restart (bug #16). Broadcast the standard post-suppressed-
            // burst accelerator so every client reconciles now (Phase 0's lifecycleReconcile forward-
            // catches-up the above-cursor rows); a dropped frame still self-heals on the next lifecycle
            // edge, since books are a cursored domain. Same rule ImportApplier follows. Gated on ANY row
            // changed — adds (persisted) OR deletions (removed: incremental Removed tombstones and the
            // full-scan sweep). A delete-only suppressed scan persists nothing yet still writes tombstones
            // above the cursor, so gating on persisted alone stranded deleted books in every client's
            // library until the next lifecycle edge. A scan that changed nothing skips the nudge — no
            // above-cursor rows to reconcile, so it would only cost every client a wasted reconcile pass.
            if (suppressFirehose && (counts.persisted > 0 || counts.removed > 0)) {
                changeBus.broadcastControl(SyncControl.LibraryDataChanged)
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
    ): PersistCounts {
        // The books carried by `result.changes` are artwork-FREE: the Scanner strips Embedded/Spooled
        // covers off the diff copies (AnalyzedBook.withoutArtwork) so the volatile artwork bytes and
        // the per-scan spool path never pollute change detection. The cover-bearing copies live in
        // `result.books`. Persist must read each changed book's cover from there, or every embedded
        // cover lands as a null cover_source. Keyed by rootRelPath — stable across Added/Modified/Moved.
        val coverBearingByPath = result.books.associateBy { it.candidate.rootRelPath }

        fun coverBearing(change: AnalyzedBook): AnalyzedBook =
            coverBearingByPath[change.candidate.rootRelPath] ?: change

        // Each book carries the absolute root of the folder it was walked from. A book's folder_id
        // and its cover both resolve against THIS root — not the scan's primary rootPath — so a
        // multi-folder library attributes every book to its own folder. Resolving one folder for the
        // whole scan misplaced every non-primary-folder book, whose audio/cover then 404'd.
        fun folderRootOf(book: AnalyzedBook): String = book.folderRootPath ?: result.rootPath

        // Resolve every owning-folder root present on disk to its FolderId ONCE (fallback sentinel per
        // root handled by resolveFolderId). Built from result.books (every book on disk) so it covers
        // both the changed-book persist grouping and the whole-library folder-qualified sweep.
        val folderIdByRoot: Map<String, FolderId> =
            result.books.mapTo(mutableSetOf(), ::folderRootOf).associateWith { resolveFolderId(it) }

        // The seen set for the full-scan tombstone sweep: every book present on disk, keyed by its
        // (folderId, rootRelPath) locator — no DB, no per-book work. Folder-qualifying it stops a
        // book in one folder from masking a same-named book in another.
        val seenPaths: Set<FolderScopedPath> =
            result.books.mapTo(mutableSetOf()) {
                FolderScopedPath(folderIdByRoot.getValue(folderRootOf(it)), it.candidate.rootRelPath)
            }
        // Resolve the library's system collection ONCE per scan: ALL_BOOKS when the inbox gate
        // is off (non-held), INBOX when it is on (held). The two cases are mutually exclusive —
        // a held book must never join ALL_BOOKS or it becomes visible to all members. A resolution
        // failure must never fail the scan — fall back to null (book lands uncollected →
        // invisible to members under pure-union until an admin collects it).
        val systemCollectionId = resolveSystemCollectionId(libraryId)
        var persisted = 0
        var failed = 0
        // Running failed count from folder groups already fully processed — the per-group callback adds
        // its own in-flight failures on top for live progress ticks.
        var failedBase = 0

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
                    extractPendingCover(book, Path(folderRootOf(book)))?.let { put(book.candidate.rootRelPath, it) }
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

        // Batched persist, folder group by folder group so each book lands under the folder it was
        // walked from. Within a group it is the same chunked O(chunks) write. Progress and counts
        // accumulate across groups so the PERSISTING bar and the Completed summary stay whole-scan.
        // (An OutOfMemoryError throws PersistAbortedByOom out of the group, carrying its own partial
        // counts, so this accumulation only needs to be correct on the normal path.)
        var processedBase = 0
        for ((folderRoot, group) in changedBooks.groupBy(::folderRootOf)) {
            val groupResult =
                ingest.resolveOrInsertAll(
                    libraryId = libraryId,
                    folderId = folderIdByRoot.getValue(folderRoot),
                    books = group,
                    coversByBook = coversByBook,
                    systemCollectionId = systemCollectionId,
                    identityMaps = identityMaps,
                ) { processedInGroup, failedInGroup ->
                    val processedTotal = processedBase + processedInGroup
                    failed = failedBase + failedInGroup
                    if (processedTotal - lastTickAt >= persistProgressStride || processedTotal == toPersist) {
                        lastTickAt = processedTotal
                        emitPersistProgress(processedTotal)
                    }
                }
            persisted += groupResult.persisted
            failedBase += groupResult.failed
            processedBase += group.size
        }
        failed = failedBase

        // Incremental Removed changes tombstone the book at their path immediately so deletions reflow
        // without waiting for the next Full scan. For Full scans the softDeleteAbsentByPaths sweep below
        // would catch it too, so the overlap is harmless. The tombstone count feeds the reconcile-nudge
        // gate so a delete-only suppressed scan still broadcasts.
        var removed = applyIncrementalRemovals(result, folderIdByRoot)

        // Final tick at the total so the bar lands on 100% before the terminal Completed, even if the
        // last chunk boundary didn't fall exactly on the final book.
        if (toPersist > 0 && lastTickAt != toPersist) emitPersistProgress(toPersist)

        if (result.scope is ScanScope.Full) {
            // Sentinel guard: if ANY walked root failed to resolve to a live folder (folder soft-deleted
            // mid-scan, or a stale bundle path), its books carry the "unknown" sentinel folder_id and are
            // absent from every real folder's seen set — the sweep would then tombstone that folder's live
            // books (the original library-wide corruption). Skip the sweep entirely and log loudly; the
            // next clean Full scan reconciles genuine removals once every root resolves.
            if (folderIdByRoot.values.any { it == UNKNOWN_FOLDER_ID }) {
                log.error {
                    "Skipping full-scan tombstone sweep for library ${libraryId.value}: a walked root did " +
                        "not resolve to a live folder (sentinel folder_id present) — sweeping would wrongly " +
                        "tombstone live books."
                }
            } else {
                // Path-based sweep is safe: every book present on disk (including any that failed
                // to persist) is in seenPaths, so the sweep never tombstones a present book. Its
                // tombstone count joins the incremental-removal count so a full rescan whose ONLY
                // change is a sweep-caught deletion still fires the reconcile nudge.
                removed += ingest.softDeleteAbsentByPaths(libraryId, seenPaths)
            }
        }
        return PersistCounts(persisted, failed, removed)
    }

    /**
     * Tombstones the book at each incremental [ChangeEventDto.Removed]'s path immediately, so deletions
     * reflow without waiting for the next Full scan. Idempotent — a no-op when the book is already
     * deleted or never existed. Each Removed carries its own owning-folder root (from the prior snapshot);
     * we resolve THAT folder — not the scan's primary root — so a removal in a non-primary folder
     * tombstones the right book and never a same-relpath book in another folder. A root that resolves to
     * the sentinel yields a no-op (the sentinel id matches no live book), so a stale root is harmless.
     * Falls back to the primary root for a pre-attribution Removed (null folder).
     *
     * Returns the number of books actually tombstoned (a no-op removal — already gone — counts 0),
     * so the caller can broadcast the reconcile nudge for a delete-only suppressed scan.
     */
    private suspend fun applyIncrementalRemovals(
        result: ScanResult,
        folderIdByRoot: Map<String, FolderId>,
    ): Int {
        val removedChanges = result.changes.filterIsInstance<ChangeEventDto.Removed>()
        if (removedChanges.isEmpty()) return 0
        val folderIdForRemoved: Map<String, FolderId> =
            removedChanges
                .mapTo(mutableSetOf()) { it.folderRootPath ?: result.rootPath }
                .associateWith { folderIdByRoot[it] ?: resolveFolderId(it) }
        var tombstoned = 0
        for (change in removedChanges) {
            val root = change.folderRootPath ?: result.rootPath
            try {
                tombstoned += ingest.softDeleteByPath(folderIdForRemoved.getValue(root), change.rootRelPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn(e) { "softDeleteByPath failed for ${change.rootRelPath} — continuing" }
            }
        }
        return tombstoned
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
        scanRoot: Path,
    ): PendingCover? {
        if (coverImageStore == null) return null
        return when (val cover = analyzed.cover) {
            null -> {
                null
            }

            is CoverSource.Filesystem -> {
                val coverPath = Path(scanRoot, cover.file.relPath)
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
                runCatching { Path(cover.path).readBytes() }
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
            UNKNOWN_FOLDER_ID
        }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/**
 * Counts of books touched during [BookPersister.persistAll]: [persisted] (Added/Modified/Moved
 * written) vs [failed], plus [removed] — the tombstones written by the incremental Removed path and
 * the full-scan sweep. [removed] is NOT part of the [persisted] tally; it exists so the caller can
 * broadcast the reconcile nudge after a delete-only suppressed scan, which persists nothing yet
 * still writes rows above every client cursor.
 */
internal data class PersistCounts(
    val persisted: Int,
    val failed: Int,
    val removed: Int,
)

/**
 * Builds a [com.calypsan.listenup.api.dto.scanner.ScanResultSummary] from this result, stamping
 * in the [PersistCounts] that [BookPersister.persistAll] gathered. This is the persister's own
 * overload — [com.calypsan.listenup.server.scanner.toSummary] is used by the Scanner before
 * persistence counts are known.
 */
internal fun ScanResult.toSummary(counts: PersistCounts): com.calypsan.listenup.api.dto.scanner.ScanResultSummary =
    toSummary().copy(persisted = counts.persisted, failed = counts.failed)
