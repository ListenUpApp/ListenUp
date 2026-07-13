package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.EmbeddedScanCounters
import com.calypsan.listenup.api.dto.scanner.MetadataStatus
import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.dto.scanner.UnsupportedFormatCount
import com.calypsan.listenup.api.dto.scanner.withoutArtwork
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.event.ScanBookRef
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import com.calypsan.listenup.server.scanner.pipeline.Analyzer
import com.calypsan.listenup.server.scanner.pipeline.BookAnalysisFailure
import com.calypsan.listenup.server.scanner.pipeline.NoRecognizedAudio
import com.calypsan.listenup.server.scanner.pipeline.Differ
import com.calypsan.listenup.server.scanner.pipeline.Grouper
import com.calypsan.listenup.server.scanner.pipeline.Walker
import com.calypsan.listenup.server.scanner.sidecar.SidecarParser
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.io.isUnder
import com.calypsan.listenup.server.io.relativeTo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlin.concurrent.Volatile
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = loggerFor<Scanner>()

/** Minimum interval between throttled ANALYZING [ScanEvent.Progress] emissions. */
private const val PROGRESS_THROTTLE_MS = 200L

/** Maximum number of recently-matched books carried in [ScanEvent.Progress.recentBooks]. */
private const val RECENT_BOOKS_CAP = 8

/**
 * Top-level scanner orchestrator for a single [Library]. Wires the four
 * pipeline stages (Walker → Grouper → Analyzer → Differ) and tracks the
 * most recent scan result in memory for cross-scan diffing.
 *
 * The Scanner exposes two work methods:
 *
 *  - [runFullScan] — walks every folder registered under [library],
 *    aggregates the results, diffs against the previous full scan's
 *    snapshot, and updates [lastResult]. Emits
 *    `Started → Progress* → Change* → Completed` events on the [eventBus].
 *  - [runIncremental] — walks just one book-root subtree (belonging to one
 *    of the library's folders), analyzes only those books, diffs against the
 *    matching subset of the previous snapshot, and patches [lastResult] in
 *    place. Lets the watcher trigger fast per-book updates without paying for
 *    a full library walk.
 *
 * **Concurrency.** Both work methods assume they're called serially — the
 * single-flight guarantee comes from [ScanCoordinator]. The Scanner does
 * NOT take its own lock; calling either method concurrently from outside
 * the coordinator is a programming error.
 *
 * **`@Volatile lastResult`.** Read by `lastResult()` from arbitrary
 * threads; written only from inside the coordinator's mutex.
 */
internal class Scanner(
    private val library: Library,
    private val metadataReader: AbsMetadataReader,
    private val embeddedMetadataParser: EmbeddedMetadataParser,
    private val eventBus: MutableSharedFlow<ScanEvent>,
    private val scanResultBus: MutableSharedFlow<ScanResult>,
    private val parseSubtitle: Boolean = false,
    private val sidecarParsers: List<SidecarParser> = emptyList(),
    private val metadataPrecedence: MetadataPrecedence = MetadataPrecedence.DEFAULT,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val correlationIdFactory: () -> String = { Uuid.random().toString() },
    private val coverSpool: CoverSpool? = null,
) : ScannerResultPort {
    @Volatile
    private var lastResult: ScanResult? = null

    // Set once the orchestrator rebuilds the bundle (a folder was added/removed). A scan that was
    // already in flight then walked a stale folder set; publishing its result would let BookPersister
    // sweep the newly-added folder's books (absent from this scan's seen set). Once superseded, both
    // emit sites drop the result instead of handing it to the persister (A8).
    @Volatile
    private var superseded = false

    override fun lastResult(): ScanResult? = lastResult

    override fun markSuperseded() {
        superseded = true
    }

    suspend fun runFullScan(): ScanResult {
        val correlationId = correlationIdFactory()
        val started = clock()
        logger.info { "scan started: library=${library.id.value} corr=$correlationId" }
        // Use the first folder path as the canonical rootPath for the ScanResult
        // and event label; all folders are walked but share a single correlation id.
        val primaryRootPath = library.folders.firstOrNull()?.rootPath ?: library.id.value
        eventBus.emit(ScanEvent.Started(correlationId, library.id, primaryRootPath))

        // Walk and group each folder independently so each Analyzer below can be
        // anchored to the correct folder root. Each file's relPath is relative to
        // the folder root it was walked from; using the wrong root causes
        // Path(root, relPath) to resolve a nonexistent path, failing embedded-
        // metadata parsing for every book in non-first folders.
        var totalFileCount = 0
        var totalCandidateCount = 0
        val folderRoots = mutableListOf<Path>()
        // The ORIGINAL configured root-path string per folder — stamped onto each book below so the
        // persister resolves folder_id by an exact library_folders.root_path match (a Path round-trip
        // could differ). Parallel to [folderRoots].
        val folderRootPaths = mutableListOf<String>()
        val candidatesPerFolder = mutableListOf<List<CandidateBook>>()
        // A full scan is authoritative for library-wide absence ONLY if every configured root was
        // actually walked. An unreachable/unreadable root (a dropped NAS/SMB mount, a permission
        // change) walks empty — treating that as authoritative would let BookPersister sweep every
        // live book under it into a tombstone. Track reachability and surface the failure honestly.
        var fullScanAuthoritative = true
        val rootErrors = mutableListOf<ScanError>()

        for (folder in library.folders) {
            val rootPath = folder.rootPath ?: continue
            val folderRoot = Path(rootPath)
            // Reachability gate: if the root is not a readable directory right now, skip it, surface a
            // typed warning, and mark the whole full scan NON-AUTHORITATIVE so the tombstone sweep is
            // suppressed downstream. This is the disk-reachability analogue of BookPersister's
            // folder-row sentinel guard. A reachable-but-empty root is NOT flagged — an emptied
            // library is a legitimate sweep.
            if (SystemFileSystem.metadataOrNull(folderRoot)?.isDirectory != true) {
                logger.error {
                    "scan: configured root '$rootPath' is unreachable or unreadable — skipping it and " +
                        "marking this scan non-authoritative (tombstone sweep suppressed) corr=$correlationId"
                }
                rootErrors += ScanError.LibraryPathNotFound(correlationId = correlationId, path = rootPath)
                fullScanAuthoritative = false
                continue
            }
            val files = Walker().walk(folderRoot).toList()
            val candidates = Grouper().group(files.asFlow()).toList()
            totalFileCount += files.size
            totalCandidateCount += candidates.size
            folderRoots += folderRoot
            folderRootPaths += rootPath
            candidatesPerFolder += candidates
        }

        emitProgress(correlationId, ScanPhase.WALKING, totalFileCount, 0, 0)

        emitProgress(correlationId, ScanPhase.GROUPING, totalFileCount, 0, 0)

        emitProgress(
            correlationId,
            ScanPhase.ANALYZING,
            totalFileCount,
            0,
            0,
            totalFiles = totalFileCount,
            booksTotal = totalCandidateCount,
        )

        // Group the previous scan's books by owning folder so each folder's fingerprint cache is
        // keyed within that folder only — a bare-rootRelPath cache aliases a book to a same-relpath
        // book in ANOTHER folder, reusing the wrong analysis (A11). Each pass below builds its own
        // path-keyed map from its folder's bucket.
        val previousByFolder = lastResult?.books.orEmpty().groupBy { it.folderRootPath }

        // Analyze each folder with an Analyzer anchored to that folder's root.
        // Aggregated books and errors replace the single-pass result; the Differ
        // runs once over the full aggregated set after all folders are processed.
        val allBooks = mutableListOf<AnalyzedBook>()
        val allErrors = mutableListOf<ScanError>()
        var authorsMatched = 0
        var totalDurationMs = 0L
        var currentFile: String? = null
        var recentBooks: List<ScanBookRef> = emptyList()

        for (i in folderRoots.indices) {
            val previousByPath =
                previousByFolder[folderRootPaths[i]]
                    .orEmpty()
                    .associateBy { it.candidate.rootRelPath }
            val analyzer =
                Analyzer(
                    folderRoots[i],
                    metadataReader,
                    embeddedMetadataParser,
                    parseSubtitle,
                    sidecarParsers,
                    metadataPrecedence,
                )
            val pass =
                collectAnalyzed(
                    analyzer,
                    candidatesPerFolder[i],
                    correlationId,
                    totalFileCount,
                    folderRoots[i],
                    previousByPath,
                )
            // Stamp every book in this pass (fresh AND fingerprint-cache reuses) with its owning
            // folder's root so the persister attributes it to the correct library_folders row.
            allBooks += pass.books.map { it.copy(folderRootPath = folderRootPaths[i]) }
            allErrors += pass.errors
            authorsMatched += pass.authorsMatched
            totalDurationMs += pass.totalDurationMs
            if (pass.currentFile != null) currentFile = pass.currentFile
            if (pass.recentBooks.isNotEmpty()) recentBooks = pass.recentBooks
        }

        emitProgress(
            correlationId,
            ScanPhase.DIFFING,
            totalFileCount,
            allBooks.size,
            allErrors.size,
            totalFiles = totalFileCount,
            booksTotal = totalCandidateCount,
            authorsMatched = authorsMatched,
            totalDurationMs = totalDurationMs,
            currentFile = currentFile,
            recentBooks = recentBooks,
        )
        // Strip artwork from both diff sides so unchanged books don't falsely show as Modified.
        // lastResult is already artwork-free (stripped at the end of the previous scan); strip
        // the new books for the diff so both sides are comparable without artwork bytes.
        val previousStripped = lastResult?.books.orEmpty() // already stripped from previous scan
        val booksStripped = allBooks.map { it.withoutArtwork() }
        val changes = Differ().diff(booksStripped.asFlow(), previousStripped).toList()
        changes.forEach { eventBus.emit(ScanEvent.Change(correlationId, library.id, it)) }

        val result =
            ScanResult(
                correlationId = correlationId,
                rootPath = primaryRootPath,
                books = allBooks, // artwork-bearing: BookPersister needs these to write covers to disk
                changes = changes, // artwork-free: Differ used stripped books
                errors = allErrors + rootErrors,
                durationMs = clock() - started,
                filesWalked = totalFileCount,
                filesSkipped = 0,
                scope = ScanScope.Full,
                // Non-authoritative when any configured root was unreachable — suppresses the sweep.
                fullScanAuthoritative = fullScanAuthoritative,
            )
        // Store a stripped copy in lastResult so artwork bytes are not retained between scans.
        lastResult = result.withoutArtwork()
        return publishFullScanResult(result, correlationId)
    }

    /**
     * Hands the full-scan [result] to [BookPersister] via [scanResultBus] — UNLESS this scanner was
     * superseded by a bundle rebuild (a folder was added/removed) while the scan was in flight. A
     * superseded scan walked a stale folder set, so its `seenPaths` omit the newly-added folder;
     * publishing it would let the persister's tombstone sweep wrongly delete that folder's live books
     * (A8). In that case the result is dropped and the new bundle's next scan reconciles. The Scanner
     * never emits `ScanEvent.Completed` here — that fires only after the persister has committed.
     */
    private suspend fun publishFullScanResult(
        result: ScanResult,
        correlationId: String,
    ): ScanResult {
        if (superseded) {
            logger.warn {
                "scan superseded by a bundle rebuild (folder add/remove) — dropping stale full-scan " +
                    "result [library=${library.id.value}] corr=$correlationId (sweep suppressed)"
            }
            return result
        }
        scanResultBus.emit(result)
        logger.info {
            "scan walk complete [library=${library.id.value}]: ${formatScanCompleteLog(
                result.toSummary(),
            )} corr=$correlationId"
        }
        return result
    }

    /**
     * Re-walks just [bookRoot] and patches the affected entries in
     * [lastResult]. The [Differ] runs against the subset of the previous
     * snapshot whose books fall under [bookRoot], so [ChangeEventDto]
     * emissions are correctly scoped — a previously-analyzed book that
     * disappeared during the incremental shows up as `Removed`.
     *
     * Identifies which library folder owns [bookRoot] to compute the
     * relative path prefix. Falls back to the first folder when none match.
     *
     * If the bookRoot directory no longer exists, all previously-known
     * books at or under that path are emitted as Removed.
     */
    suspend fun runIncremental(bookRoot: Path) {
        val correlationId = correlationIdFactory()
        val started = clock()
        logger.info { "incremental scan started: library=${library.id.value} root=$bookRoot corr=$correlationId" }
        eventBus.emit(ScanEvent.Started(correlationId, library.id, bookRoot.toString()))

        // Identify which folder owns this subtree to compute the relative path.
        val owningFolder =
            library.folders.firstOrNull { folder ->
                folder.rootPath?.let { bookRoot.isUnder(Path(it)) } ?: false
            }
        // The ORIGINAL configured root-path string (exact library_folders.root_path) this subtree
        // belongs to — stamped onto each book so the persister resolves the right folder_id.
        val folderRootPath =
            owningFolder?.rootPath
                ?: library.folders.firstOrNull()?.rootPath
                ?: bookRoot.toString()
        val folderRoot = Path(folderRootPath)

        val walker = Walker()
        val rawFiles = walker.walk(bookRoot).toList()
        val prefix = bookRoot.relativeTo(folderRoot).replace('\\', '/')
        val rebasedFiles =
            rawFiles.map { entry ->
                if (prefix.isEmpty()) entry else entry.copy(relPath = "$prefix/${entry.relPath}")
            }

        emitProgress(correlationId, ScanPhase.WALKING, rebasedFiles.size, 0, 0)
        val grouper = Grouper()
        emitProgress(correlationId, ScanPhase.GROUPING, rebasedFiles.size, 0, 0)
        val candidates = grouper.group(rebasedFiles.asFlow()).toList()

        emitProgress(correlationId, ScanPhase.ANALYZING, rebasedFiles.size, 0, 0, totalFiles = rebasedFiles.size)
        val analyzer =
            Analyzer(
                folderRoot,
                metadataReader,
                embeddedMetadataParser,
                parseSubtitle,
                sidecarParsers,
                metadataPrecedence,
            )
        // For incremental scans the dirty-check only covers the affected subtree;
        // the untouched books are preserved via previousUntouched below.
        val previousByPath = lastResult?.books.orEmpty().associateBy { it.candidate.rootRelPath }
        val pass = collectAnalyzed(analyzer, candidates, correlationId, rebasedFiles.size, folderRoot, previousByPath)
        // Stamp each book with its owning folder's root so the persister attributes it correctly.
        val books = pass.books.map { it.copy(folderRootPath = folderRootPath) }
        val errors = pass.errors

        val (previousAffected, previousUntouched) =
            partitionBooksUnder(
                bookRoot,
                folderRoot,
                folderRootPath,
                lastResult?.books.orEmpty(), // already stripped from previous scan
            )
        // Strip artwork from the new books before diffing so both sides are comparable without
        // artwork bytes (previousAffected is already stripped; strip books to match).
        val booksStripped = books.map { it.withoutArtwork() }
        val changes = Differ().diff(booksStripped.asFlow(), previousAffected).toList()
        changes.forEach { eventBus.emit(ScanEvent.Change(correlationId, library.id, it)) }

        val patchedStripped = previousUntouched + booksStripped
        val durationMs = clock() - started
        val rootRelPath = bookRoot.relativeTo(folderRoot).replace('\\', '/')
        val subtreeScope = ScanScope.Subtree(rootRelPath)
        val primaryRootPath = library.folders.firstOrNull()?.rootPath ?: library.id.value

        // Build the artwork-bearing result for BookPersister (so covers get written to disk).
        val incrementalResult =
            lastResult?.copy(
                // Stamp THIS scan's id — never inherit lastResult's. Inheriting it made the
                // incremental spool to its real correlationId but clear/report the previous full
                // scan's, leaking the live spool dir and mis-keying ScanEvent.Completed.
                correlationId = correlationId,
                books = books, // artwork-bearing for persist
                changes = changes,
                errors = errors,
                durationMs = durationMs,
                filesWalked = rebasedFiles.size,
                scope = subtreeScope,
            ) ?: ScanResult(
                correlationId = correlationId,
                rootPath = primaryRootPath,
                books = books, // artwork-bearing for persist
                changes = changes,
                errors = errors,
                durationMs = durationMs,
                filesWalked = rebasedFiles.size,
                filesSkipped = 0,
                scope = subtreeScope,
            )
        // Store a stripped copy in lastResult so artwork bytes are not retained between scans.
        lastResult = incrementalResult.copy(books = patchedStripped)
        logger.info {
            "incremental scan complete: library=${library.id.value} root=$bookRoot" +
                " books=${books.size} changes=${changes.size} errors=${errors.size} in ${durationMs}ms corr=$correlationId"
        }
        // A superseded bundle's incremental result is stale too — drop it rather than let its
        // Removed changes tombstone against a folder set the new bundle has already moved past (A8).
        if (superseded) {
            logger.warn {
                "incremental scan superseded by a bundle rebuild — dropping stale result " +
                    "[library=${library.id.value}] corr=$correlationId"
            }
            return
        }
        // BookPersister emits ScanEvent.Completed after persisting this incremental result.
        scanResultBus.emit(incrementalResult)
    }

    /**
     * Drains the [Analyzer] flow for [candidates], aggregating analyzed books,
     * errors, and the live progress signals (authors matched, total duration,
     * current file, recent books). Emits throttled ANALYZING
     * [ScanEvent.Progress] ticks while collecting, plus one unconditional final
     * tick so the last batch's stats land even when it falls inside the throttle
     * window. [errorRoot] is the folder root passed to [toScanError] for
     * resolving failing-book paths.
     *
     * Dirty-check: before sending a [CandidateBook] to the [Analyzer],
     * compute its file fingerprint — the ordered list of `(inode, mtimeMs, size)`
     * tuples over every file in the candidate. If [previousByPath] contains an
     * entry at the same `rootRelPath` whose candidate fingerprint matches, the
     * previous [AnalyzedBook] is reused directly — the audio files are not
     * re-parsed. On a re-scan of an unchanged 1150-book library this reduces
     * the embedded-metadata parse count from ~1150 to ~0.
     *
     * Fingerprint comparison is exact: any field change (mtime, size, or inode)
     * triggers a full re-analysis for that book. Books at a new `rootRelPath`
     * always re-analyze; books at an existing path whose fingerprint differs
     * always re-analyze. The [Differ] downstream is unaffected — it still runs
     * over the full (possibly-reused) [AnalyzedBook] list and produces correct
     * change events.
     */
    private suspend fun collectAnalyzed(
        analyzer: Analyzer,
        candidates: List<CandidateBook>,
        correlationId: String,
        fileCount: Int,
        errorRoot: Path,
        previousByPath: Map<String, AnalyzedBook> = emptyMap(),
    ): AnalyzePass {
        val books = mutableListOf<AnalyzedBook>()
        val errors = mutableListOf<ScanError>()
        val authorsSeen = mutableSetOf<String>()
        var durationMsSum = 0L
        val recent = ArrayDeque<ScanBookRef>()
        var currentFile: String? = null
        var lastEmit = clock()

        // Partition candidates into cache hits (fingerprint unchanged) and misses (must re-analyze).
        val (cacheHits, toAnalyze) =
            candidates.partition { candidate ->
                val previous = previousByPath[candidate.rootRelPath] ?: return@partition false
                fingerprintMatches(candidate, previous.candidate)
            }
        logger.debug {
            "scan cache: library=${library.id.value} hits=${cacheHits.size} misses=${toAnalyze.size} total=${candidates.size}"
        }

        // Accept all cache hits immediately — no parse, no IO.
        for (cached in cacheHits) {
            val book = previousByPath.getValue(cached.rootRelPath)
            books += book
            book.authors.forEach { authorsSeen += it }
            durationMsSum += book.embedded?.durationMs ?: 0L
        }

        analyzer.analyze(toAnalyze.asFlow()).collect { result ->
            result
                .onSuccess { book ->
                    books += coverSpool?.spoolCover(correlationId, book) ?: book
                    book.authors.forEach { authorsSeen += it }
                    durationMsSum += book.embedded?.durationMs ?: 0L
                    currentFile = book.candidate.rootRelPath
                    recent.addLast(ScanBookRef(title = book.title, author = book.authors.firstOrNull().orEmpty()))
                    while (recent.size > RECENT_BOOKS_CAP) recent.removeFirst()
                }.onFailure { t ->
                    val relPath = (t as? BookAnalysisFailure)?.rootRelPath ?: errorRoot.toString()
                    // A folder with no recognized audio is an expected skip (not a book), not a
                    // fault — log it as a calm, actionable one-liner without a stacktrace. Genuine
                    // analysis faults keep the full throwable so they stay diagnosable.
                    val skip = (t as? BookAnalysisFailure)?.cause as? NoRecognizedAudio
                    if (skip != null) {
                        logger.warn { "skipped: path=$relPath library=${library.id.value} — ${skip.message}" }
                    } else {
                        logger.warn(t) { "analyze failed: path=$relPath library=${library.id.value}" }
                    }
                    errors += toScanError(t, errorRoot)
                }
            val now = clock()
            if (now - lastEmit >= PROGRESS_THROTTLE_MS) {
                emitProgress(
                    correlationId,
                    ScanPhase.ANALYZING,
                    fileCount,
                    books.size,
                    errors.size,
                    totalFiles = fileCount,
                    booksTotal = candidates.size,
                    authorsMatched = authorsSeen.size,
                    totalDurationMs = durationMsSum,
                    currentFile = currentFile,
                    recentBooks = recent.toList(),
                )
                lastEmit = now
            }
        }
        // Final ANALYZING tick so the last batch's stats land even when under the throttle window.
        emitProgress(
            correlationId,
            ScanPhase.ANALYZING,
            fileCount,
            books.size,
            errors.size,
            totalFiles = fileCount,
            booksTotal = candidates.size,
            authorsMatched = authorsSeen.size,
            totalDurationMs = durationMsSum,
            currentFile = currentFile,
            recentBooks = recent.toList(),
        )
        return AnalyzePass(
            books = books,
            errors = errors,
            authorsMatched = authorsSeen.size,
            totalDurationMs = durationMsSum,
            currentFile = currentFile,
            recentBooks = recent.toList(),
        )
    }

    /**
     * Final aggregates from a [collectAnalyzed] pass. Carries the enriched
     * stats (authors matched, total duration, current file, recent books)
     * forward so the DIFFING progress tick can preserve them rather than
     * regressing to defaults right before completion.
     */
    private data class AnalyzePass(
        val books: List<AnalyzedBook>,
        val errors: List<ScanError>,
        val authorsMatched: Int,
        val totalDurationMs: Long,
        val currentFile: String?,
        val recentBooks: List<ScanBookRef>,
    )

    /**
     * Returns true when every file in [current] has the same
     * `(inode, mtimeMs, size)` triple at the same `relPath` as the
     * corresponding file in [previous], in the same order.
     *
     * A file with `inode = null` (Windows FAT / SMB mounts that don't
     * expose stable inodes) is compared by `(relPath, mtimeMs, size)` only,
     * matching the Differ's degraded-mode behaviour.
     *
     * When the file lists differ in length or in any of the fingerprint
     * fields, the candidate is re-analyzed.
     */
    private fun fingerprintMatches(
        current: CandidateBook,
        previous: CandidateBook,
    ): Boolean {
        val currentFiles = current.files
        val previousFiles = previous.files
        if (currentFiles.size != previousFiles.size) return false
        return currentFiles.zip(previousFiles).all { (c, p) ->
            c.relPath == p.relPath &&
                c.mtimeMs == p.mtimeMs &&
                c.size == p.size &&
                (c.inode == null || p.inode == null || c.inode == p.inode)
        }
    }

    private fun partitionBooksUnder(
        bookRoot: Path,
        folderRoot: Path,
        folderRootPath: String,
        books: List<AnalyzedBook>,
    ): Pair<List<AnalyzedBook>, List<AnalyzedBook>> {
        val rootPrefix = bookRoot.relativeTo(folderRoot).replace('\\', '/')
        return books.partition { book ->
            // A book is "affected" by this incremental only if it belongs to the SAME folder as the
            // scanned subtree. The previous snapshot spans EVERY folder, and each rootRelPath is
            // relative to its OWN folder — so a path-prefix match alone (especially the empty prefix
            // when bookRoot == folderRoot) wrongly claimed every other folder's books as affected,
            // and the Differ then emitted Removed for them (mass cross-folder tombstoning). Filtering
            // on the stamped folderRootPath first prevents that. A null folderRootPath (legacy /
            // unattributed) can't be excluded, so it falls through to the prefix check as before.
            if (book.folderRootPath != null && book.folderRootPath != folderRootPath) {
                return@partition false
            }
            val rel = book.candidate.rootRelPath
            if (rootPrefix.isEmpty()) {
                true // bookRoot is the folder root → all of THIS folder's books are affected
            } else {
                rel == rootPrefix || rel.startsWith("$rootPrefix/")
            }
        }
    }

    private suspend fun emitProgress(
        correlationId: String,
        phase: ScanPhase,
        filesWalked: Int,
        booksAnalyzed: Int,
        errors: Int,
        totalFiles: Int = 0,
        booksTotal: Int = 0,
        authorsMatched: Int = 0,
        totalDurationMs: Long = 0,
        currentFile: String? = null,
        recentBooks: List<ScanBookRef> = emptyList(),
    ) {
        eventBus.emit(
            ScanEvent.Progress(
                correlationId = correlationId,
                libraryId = library.id,
                phase = phase,
                filesWalked = filesWalked,
                booksAnalyzed = booksAnalyzed,
                errors = errors,
                totalFiles = totalFiles,
                booksTotal = booksTotal,
                authorsMatched = authorsMatched,
                totalDurationMs = totalDurationMs,
                currentFile = currentFile,
                recentBooks = recentBooks,
            ),
        )
    }

    /**
     * Maps a per-book analysis failure to a [ScanError.FileUnreadable]. When the
     * throwable is a [BookAnalysisFailure] it carries the candidate's
     * `rootRelPath`, so the error names the *failing book's* directory rather
     * than the folder root — the operator can navigate straight to it.
     */
    private fun toScanError(
        t: Throwable,
        folderRoot: Path,
    ): ScanError {
        val path =
            (t as? BookAnalysisFailure)
                ?.let { Path(folderRoot, it.rootRelPath).toString() }
                ?: folderRoot.toString()
        return ScanError.FileUnreadable(
            path = path,
            debugInfo = t.message ?: "unknown error",
        )
    }
}

internal fun ScanResult.toSummary(): ScanResultSummary =
    ScanResultSummary(
        correlationId = correlationId,
        totalBooks = books.size,
        added = changes.count { it is ChangeEventDto.Added },
        modified = changes.count { it is ChangeEventDto.Modified },
        removed = changes.count { it is ChangeEventDto.Removed },
        moved = changes.count { it is ChangeEventDto.Moved },
        errors = errors.size,
        durationMs = durationMs,
        filesWalked = filesWalked,
        embedded = books.toEmbeddedScanCounters(),
    )

/**
 * Single-pass aggregation over [AnalyzedBook] embedded-metadata signals.
 * Books with `embeddedStatus = null` (no audio file in the candidate) are
 * skipped — they're not part of the embedded-enrichment population.
 */
internal fun List<AnalyzedBook>.toEmbeddedScanCounters(): EmbeddedScanCounters {
    var parsed = 0
    var unsupported = 0
    var parseErrors = 0
    var withChapters = 0
    var withArtwork = 0
    var unrecognisedMagic = 0
    val perFormat = mutableMapOf<com.calypsan.listenup.domain.embeddedmeta.AudioFormat, Int>()

    for (book in this) {
        when (val status = book.embeddedStatus) {
            null -> {
                // Candidate had no audio file; not part of the embedded-eligible population.
            }

            is MetadataStatus.Available -> {
                parsed += 1
                val embedded = book.embedded ?: continue
                if (embedded.chapters.isNotEmpty()) withChapters += 1
                if (embedded.artwork != null) withArtwork += 1
            }

            is MetadataStatus.UnsupportedFormat -> {
                unsupported += 1
                val format = status.format
                if (format == null) {
                    unrecognisedMagic += 1
                } else {
                    perFormat[format] = (perFormat[format] ?: 0) + 1
                }
            }

            is MetadataStatus.ParseError -> {
                parseErrors += 1
            }
        }
    }

    return EmbeddedScanCounters(
        parsed = parsed,
        unsupported = unsupported,
        parseErrors = parseErrors,
        withChapters = withChapters,
        withArtwork = withArtwork,
        unsupportedFormats = perFormat.entries.map { (f, c) -> UnsupportedFormatCount(format = f, count = c) },
        unrecognisedMagic = unrecognisedMagic,
    )
}

/**
 * Renders [ScanResultSummary] as the human-readable scan-complete log
 * line. Embedded counters are appended only when the scan actually had
 * embedded-eligible books, so legacy zero-counter scans look unchanged.
 */
internal fun formatScanCompleteLog(summary: ScanResultSummary): String =
    buildString {
        append(summary.totalBooks).append(" books, ")
        append(summary.added + summary.modified + summary.removed + summary.moved).append(" changes, ")
        append(summary.errors).append(" errors in ")
        append(summary.durationMs).append("ms")

        val e = summary.embedded
        val embeddedTotal = e.parsed + e.unsupported + e.parseErrors
        if (embeddedTotal == 0) return@buildString

        append(" | embedded: ")
        append(e.parsed).append(" parsed (")
        append(e.withChapters).append(" w/chapters, ")
        append(e.withArtwork).append(" w/artwork)")
        if (e.unsupported > 0) {
            append(", ").append(e.unsupported).append(" unsupported")
            if (e.unsupportedFormats.isNotEmpty()) {
                val breakdown = e.unsupportedFormats.joinToString(",") { "${it.format::class.simpleName}=${it.count}" }
                append(" [").append(breakdown).append("]")
            }
            if (e.unrecognisedMagic > 0) append(", ").append(e.unrecognisedMagic).append(" unrecognised")
        }
        if (e.parseErrors > 0) append(", ").append(e.parseErrors).append(" parse errors")
    }
