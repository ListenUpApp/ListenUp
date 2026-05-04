package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.pipeline.Analyzer
import com.calypsan.listenup.server.scanner.pipeline.Differ
import com.calypsan.listenup.server.scanner.pipeline.Grouper
import com.calypsan.listenup.server.scanner.pipeline.Walker
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import java.nio.file.Path
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Top-level scanner orchestrator. Wires the four pipeline stages
 * (Walker → Grouper → Analyzer → Differ) and tracks the most recent scan
 * result in memory for cross-scan diffing.
 *
 * The Scanner exposes two work methods:
 *
 *  - [runFullScan] — walks the entire library, analyzes every book,
 *    diffs against the previous full scan's snapshot, and updates
 *    [lastResult]. Emits `Started → Progress* → Change* → Completed`
 *    events on the [eventBus].
 *  - [runIncremental] — walks just one book-root subtree, analyzes only
 *    those books, diffs against the matching subset of the previous
 *    snapshot, and patches the affected entries in [lastResult] in place.
 *    Lets the watcher trigger fast per-book updates without paying for a
 *    full library walk.
 *
 * **Concurrency.** Both work methods assume they're called serially — the
 * single-flight guarantee comes from [ScanCoordinator]. The Scanner does
 * NOT take its own lock; calling either method concurrently from outside
 * the coordinator is a programming error.
 *
 * **`@Volatile lastResult`.** Read by `lastResult()` from arbitrary
 * threads; written only from inside the coordinator's mutex.
 */
class Scanner(
    private val rootPath: Path,
    private val metadataReader: AbsMetadataReader,
    private val eventBus: MutableSharedFlow<ScanEvent>,
    private val parseSubtitle: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val correlationIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    @Volatile
    private var lastResult: ScanResult? = null

    fun lastResult(): ScanResult? = lastResult

    suspend fun runFullScan(): ScanResult {
        val correlationId = correlationIdFactory()
        val started = clock()
        eventBus.emit(ScanEvent.Started(correlationId, rootPath.toString()))

        val (books, errors, filesWalked) = analyzeSubtree(bookRoot = rootPath, correlationId = correlationId)

        emitProgress(correlationId, ScanPhase.DIFFING, filesWalked, books.size, errors.size)
        val previous = lastResult?.books.orEmpty()
        val changes = Differ().diff(books.asFlow(), previous).toList()
        changes.forEach { eventBus.emit(ScanEvent.Change(correlationId, it)) }

        val result =
            ScanResult(
                correlationId = correlationId,
                rootPath = rootPath.toString(),
                books = books,
                changes = changes,
                errors = errors,
                durationMs = clock() - started,
                filesWalked = filesWalked,
                filesSkipped = 0,
            )
        lastResult = result
        eventBus.emit(ScanEvent.Completed(correlationId, result.toSummary()))
        logger.info {
            "scan complete: ${books.size} books, ${changes.size} changes, ${errors.size} errors in ${result.durationMs}ms"
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
     * If the bookRoot directory no longer exists, all previously-known
     * books at or under that path are emitted as Removed.
     */
    suspend fun runIncremental(bookRoot: Path) {
        val correlationId = correlationIdFactory()
        val started = clock()
        eventBus.emit(ScanEvent.Started(correlationId, bookRoot.toString()))

        val (books, errors, filesWalked) = analyzeSubtree(bookRoot = bookRoot, correlationId = correlationId)

        val (previousAffected, previousUntouched) = partitionBooksUnder(bookRoot, lastResult?.books.orEmpty())
        val changes = Differ().diff(books.asFlow(), previousAffected).toList()
        changes.forEach { eventBus.emit(ScanEvent.Change(correlationId, it)) }

        val patched = previousUntouched + books
        val durationMs = clock() - started
        lastResult =
            lastResult?.copy(
                books = patched,
                changes = changes,
                errors = errors,
                durationMs = durationMs,
                filesWalked = filesWalked,
            ) ?: ScanResult(
                correlationId = correlationId,
                rootPath = rootPath.toString(),
                books = patched,
                changes = changes,
                errors = errors,
                durationMs = durationMs,
                filesWalked = filesWalked,
                filesSkipped = 0,
            )
        eventBus.emit(ScanEvent.Completed(correlationId, lastResult!!.toSummary()))
    }

    private suspend fun analyzeSubtree(
        bookRoot: Path,
        correlationId: String,
    ): SubtreeAnalysis {
        val prefix = rootPath.relativize(bookRoot).toString().replace('\\', '/')
        val walker = Walker()
        val grouper = Grouper()
        val analyzer = Analyzer(rootPath, metadataReader, parseSubtitle)

        emitProgress(correlationId, ScanPhase.WALKING, 0, 0, 0)
        val rebasedFiles =
            walker
                .walk(bookRoot)
                .map { entry ->
                    if (prefix.isEmpty()) {
                        entry
                    } else {
                        entry.copy(relPath = "$prefix/${entry.relPath}")
                    }
                }.toList()

        emitProgress(correlationId, ScanPhase.GROUPING, rebasedFiles.size, 0, 0)
        val candidates = grouper.group(rebasedFiles.asFlow()).toList()

        emitProgress(correlationId, ScanPhase.ANALYZING, rebasedFiles.size, 0, 0)
        val books = mutableListOf<AnalyzedBook>()
        val errors = mutableListOf<ScanError>()
        analyzer.analyze(candidates.asFlow()).toList().forEach { result ->
            result
                .onSuccess { books += it }
                .onFailure { errors += toScanError(it) }
        }
        return SubtreeAnalysis(books = books, errors = errors, filesWalked = rebasedFiles.size)
    }

    private fun partitionBooksUnder(
        bookRoot: Path,
        books: List<AnalyzedBook>,
    ): Pair<List<AnalyzedBook>, List<AnalyzedBook>> {
        val rootPrefix = rootPath.relativize(bookRoot).toString().replace('\\', '/')
        return books.partition { book ->
            val rel = book.candidate.rootRelPath
            if (rootPrefix.isEmpty()) {
                true // bookRoot is the library root → all books are affected (a full reanalysis was triggered)
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
    ) {
        eventBus.emit(
            ScanEvent.Progress(
                correlationId = correlationId,
                phase = phase,
                filesWalked = filesWalked,
                booksAnalyzed = booksAnalyzed,
                errors = errors,
            ),
        )
    }

    private fun toScanError(t: Throwable): ScanError =
        ScanError.FileUnreadable(
            path = rootPath.toString(),
            message = t.message ?: t::class.simpleName ?: "unknown error",
        )
}

private data class SubtreeAnalysis(
    val books: List<AnalyzedBook>,
    val errors: List<ScanError>,
    val filesWalked: Int,
)

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
    )
