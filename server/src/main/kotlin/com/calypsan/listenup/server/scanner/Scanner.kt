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
import com.calypsan.listenup.server.scanner.pipeline.Differ
import com.calypsan.listenup.server.scanner.pipeline.Grouper
import com.calypsan.listenup.server.scanner.pipeline.Walker
import com.calypsan.listenup.server.scanner.sidecar.SidecarParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import java.nio.file.Path
import java.util.UUID

private val logger = KotlinLogging.logger {}

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
    private val clock: () -> Long = System::currentTimeMillis,
    private val correlationIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val coverSpool: CoverSpool? = null,
) : ScannerResultPort {
    @Volatile
    private var lastResult: ScanResult? = null

    override fun lastResult(): ScanResult? = lastResult

    suspend fun runFullScan(): ScanResult {
        val correlationId = correlationIdFactory()
        val started = clock()
        // Use the first folder path as the canonical rootPath for the ScanResult
        // and event label; all folders are walked but share a single correlation id.
        val primaryRootPath = library.folders.firstOrNull()?.rootPath ?: library.id.value
        eventBus.emit(ScanEvent.Started(correlationId, library.id, primaryRootPath))

        // Walk each folder, rebase its files onto its folder-relative prefix,
        // then aggregate into a single flat list for the pipeline.
        val allFiles =
            library.folders.flatMap { folder ->
                val folderRoot = Path.of(folder.rootPath)
                val walker = Walker()
                walker
                    .walk(folderRoot)
                    .toList()
            }

        emitProgress(correlationId, ScanPhase.WALKING, allFiles.size, 0, 0)

        val grouper = Grouper()
        emitProgress(correlationId, ScanPhase.GROUPING, allFiles.size, 0, 0)
        val candidates = grouper.group(allFiles.asFlow()).toList()

        emitProgress(
            correlationId,
            ScanPhase.ANALYZING,
            allFiles.size,
            0,
            0,
            totalFiles = allFiles.size,
            booksTotal = candidates.size,
        )
        // Analyzer uses the first folder root as the libraryRoot anchor for
        // computing rootRelPath and resolving error paths. When the library has
        // multiple folders, each folder's books will be at absolute paths inside
        // those roots — the Grouper preserves absolute relPath for multi-folder
        // libraries. Using the first folder root is consistent with what the
        // watcher supplies to runIncremental.
        val primaryRoot =
            library.folders.firstOrNull()?.let { Path.of(it.rootPath) }
                ?: Path.of(library.id.value)
        val analyzer =
            Analyzer(
                primaryRoot,
                metadataReader,
                embeddedMetadataParser,
                parseSubtitle,
                sidecarParsers,
                metadataPrecedence,
            )
        val pass = collectAnalyzed(analyzer, candidates, correlationId, allFiles.size, primaryRoot)
        val books = pass.books
        val errors = pass.errors

        emitProgress(
            correlationId,
            ScanPhase.DIFFING,
            allFiles.size,
            books.size,
            errors.size,
            totalFiles = allFiles.size,
            booksTotal = candidates.size,
            authorsMatched = pass.authorsMatched,
            totalDurationMs = pass.totalDurationMs,
            currentFile = pass.currentFile,
            recentBooks = pass.recentBooks,
        )
        // Strip artwork from both diff sides so unchanged books don't falsely show as Modified.
        // lastResult is already artwork-free (stripped at the end of the previous scan); strip
        // the new books for the diff so both sides are comparable without artwork bytes.
        val previousStripped = lastResult?.books.orEmpty() // already stripped from previous scan
        val booksStripped = books.map { it.withoutArtwork() }
        val changes = Differ().diff(booksStripped.asFlow(), previousStripped).toList()
        changes.forEach { eventBus.emit(ScanEvent.Change(correlationId, library.id, it)) }

        val result =
            ScanResult(
                correlationId = correlationId,
                rootPath = primaryRootPath,
                books = books, // artwork-bearing: BookPersister needs these to write covers to disk
                changes = changes, // artwork-free: Differ used stripped books
                errors = errors,
                durationMs = clock() - started,
                filesWalked = allFiles.size,
                filesSkipped = 0,
                scope = ScanScope.Full,
            )
        // Store a stripped copy in lastResult so artwork bytes are not retained between scans.
        lastResult = result.withoutArtwork()
        // Hand the artwork-bearing result to BookPersister; it emits ScanEvent.Completed once the
        // books are persisted (see BookPersister.persist). The Scanner must NOT emit Completed here
        // — that would signal "done" before any book is queryable.
        scanResultBus.emit(result)
        logger.info { "scan walk complete [library=${library.id.value}]: ${formatScanCompleteLog(result.toSummary())}" }
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
        eventBus.emit(ScanEvent.Started(correlationId, library.id, bookRoot.toString()))

        // Identify which folder owns this subtree to compute the relative path.
        val owningFolder =
            library.folders.firstOrNull { folder ->
                bookRoot.startsWith(Path.of(folder.rootPath))
            }
        val folderRoot =
            owningFolder?.let { Path.of(it.rootPath) }
                ?: library.folders.firstOrNull()?.let { Path.of(it.rootPath) }
                ?: bookRoot

        val walker = Walker()
        val rawFiles = walker.walk(bookRoot).toList()
        val prefix = folderRoot.relativize(bookRoot).toString().replace('\\', '/')
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
        val pass = collectAnalyzed(analyzer, candidates, correlationId, rebasedFiles.size, folderRoot)
        val books = pass.books
        val errors = pass.errors

        val (previousAffected, previousUntouched) =
            partitionBooksUnder(
                bookRoot,
                folderRoot,
                lastResult?.books.orEmpty(), // already stripped from previous scan
            )
        // Strip artwork from the new books before diffing so both sides are comparable without
        // artwork bytes (previousAffected is already stripped; strip books to match).
        val booksStripped = books.map { it.withoutArtwork() }
        val changes = Differ().diff(booksStripped.asFlow(), previousAffected).toList()
        changes.forEach { eventBus.emit(ScanEvent.Change(correlationId, library.id, it)) }

        val patchedStripped = previousUntouched + booksStripped
        val durationMs = clock() - started
        val rootRelPath = folderRoot.relativize(bookRoot).toString().replace('\\', '/')
        val subtreeScope = ScanScope.Subtree(rootRelPath)
        val primaryRootPath = library.folders.firstOrNull()?.rootPath ?: library.id.value

        // Build the artwork-bearing result for BookPersister (so covers get written to disk).
        val incrementalResult =
            lastResult?.copy(
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
     */
    private suspend fun collectAnalyzed(
        analyzer: Analyzer,
        candidates: List<CandidateBook>,
        correlationId: String,
        fileCount: Int,
        errorRoot: Path,
    ): AnalyzePass {
        val books = mutableListOf<AnalyzedBook>()
        val errors = mutableListOf<ScanError>()
        val authorsSeen = mutableSetOf<String>()
        var durationMsSum = 0L
        val recent = ArrayDeque<ScanBookRef>()
        var currentFile: String? = null
        var lastEmit = clock()
        analyzer.analyze(candidates.asFlow()).collect { result ->
            result
                .onSuccess { book ->
                    books += (coverSpool?.spoolCover(correlationId, book) ?: book)
                    book.authors.forEach { authorsSeen += it }
                    durationMsSum += book.embedded?.durationMs ?: 0L
                    currentFile = book.candidate.rootRelPath
                    recent.addLast(ScanBookRef(title = book.title, author = book.authors.firstOrNull().orEmpty()))
                    while (recent.size > RECENT_BOOKS_CAP) recent.removeFirst()
                }.onFailure { errors += toScanError(it, errorRoot) }
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

    private fun partitionBooksUnder(
        bookRoot: Path,
        folderRoot: Path,
        books: List<AnalyzedBook>,
    ): Pair<List<AnalyzedBook>, List<AnalyzedBook>> {
        val rootPrefix = folderRoot.relativize(bookRoot).toString().replace('\\', '/')
        return books.partition { book ->
            val rel = book.candidate.rootRelPath
            if (rootPrefix.isEmpty()) {
                true // bookRoot is the folder root → all books in this folder are affected
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
                ?.let { folderRoot.resolve(it.rootRelPath).toString() }
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
