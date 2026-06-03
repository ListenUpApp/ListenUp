package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CoverSource
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CoverSource as SyncCoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.db.LibraryFolderTable
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val log = KotlinLogging.logger {}

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
 * No inbox auto-add: scan-time inbox auto-populate was deliberately reverted. It
 * carried a TOCTOU content leak — `BookRepository.upsert` commits the book row
 * and publishes `book.Created` to the firehose inside one transaction, at which
 * instant the book is in no collection (uncollected → public by
 * [com.calypsan.listenup.server.api.BookAccessPolicy]). The firehose subscriber
 * collects the event, sees committed-public state, and delivers the full payload
 * to every connected member *before* the separate `addToInbox` transaction can
 * quarantine it. Closing that window atomically would require the membership to
 * land in the same transaction as the book insert with the `book.Created`
 * publish deferred until after commit — a change to the shared
 * [com.calypsan.listenup.server.sync.SyncableRepository.upsert] publish-inside-
 * transaction contract that every domain depends on. Rather than risk that
 * cross-domain contract, the auto-add hook is removed; scanned books stay
 * uncollected (their existing behaviour). The inbox remains usable via the
 * deliberate admin path
 * ([com.calypsan.listenup.server.api.CollectionServiceImpl.addToInbox] /
 * `releaseBooks` / `listInbox`). Scan-auto-populate is a future phase with
 * atomic ingest designed in from the start.
 *
 * [coverImageStore] is optional: when non-null, the persister extracts cover
 * bytes from each [AnalyzedBook] (filesystem or embedded) and passes a
 * [PendingCover] to [BookIngestPort.resolveOrInsert] so the repository can
 * write the managed cover file after the book id is known. When null (e.g. in
 * pure orchestration tests), cover extraction is skipped.
 */
class BookPersister(
    private val ingest: BookIngestPort,
    private val libraryRegistry: LibraryRegistry,
    private val db: Database,
    private val scanResultBus: SharedFlow<ScanResult>,
    private val eventBus: MutableSharedFlow<ScanEvent>,
    private val scope: CoroutineScope,
    private val metrics: BookPersisterMetrics,
    private val coverImageStore: CoverImageStore? = null,
) {
    /** Launch the collector. Idempotent only in the sense that it should be called once at bootstrap. */
    fun start() {
        scope.launch {
            scanResultBus.collect { result -> persist(result) }
        }
    }

    /** Visible for tests — drive one ScanResult through without the bus. */
    internal suspend fun persist(result: ScanResult) {
        val libraryId = libraryRegistry.currentLibrary()
        // Resolve the folder whose root_path matches the scan result's rootPath.
        // Falls back to a sentinel folderId so a misconfigured folder doesn't
        // block persistence; the TODO below tracks proper error surfacing.
        val folderId = resolveFolderId(result.rootPath)

        // A FULL scan is bulk population (onboarding, re-scan): suppress the per-book firehose
        // PUBLISH so the lossy live tail (ChangeBus replay=256, DROP_OLDEST) never carries the
        // burst — an arbitrarily large library would otherwise overflow it and trip a client
        // CursorStale → catch-up spin. Revisions still bump, so the client does one clean REST
        // catch-up after the Completed below. Incremental scans ARE live deltas; they publish.
        if (result.scope is ScanScope.Full) {
            withContext(FirehoseSuppressed) { persistAll(result, libraryId, folderId) }
        } else {
            persistAll(result, libraryId, folderId)
        }

        // Completed is emitted HERE — after every book is committed and (for a full scan) the
        // tombstone sweep has run — not by the Scanner before this persist runs. `Completed` must
        // mean "the library is persisted and queryable", so the client reconciles a settled server
        // exactly once instead of racing a still-writing one (the premature-Completed bug).
        eventBus.emit(ScanEvent.Completed(result.correlationId, libraryId, result.toSummary()))
    }

    /**
     * Persists every book in [result] and, for a [ScanScope.Full] result, runs the tombstone
     * sweep. The caller decides whether this runs under [FirehoseSuppressed]; this method is
     * suppression-agnostic.
     */
    private suspend fun persistAll(
        result: ScanResult,
        libraryId: LibraryId,
        folderId: FolderId,
    ) {
        val seenIds = mutableSetOf<BookId>()
        // Use the scan result's rootPath for filesystem cover reads — aligned
        // with Analyzer's own path resolution (Analyzer.kt: rootPath.resolve(relPath)).
        val scanRoot = JPath.of(result.rootPath)
        var anyFailed = false
        for (analyzed in result.books) {
            val bookId = persistOne(analyzed, libraryId, folderId, scanRoot)
            if (bookId != null) seenIds += bookId else anyFailed = true
        }
        if (result.scope is ScanScope.Full) {
            if (anyFailed) {
                // A book failed to persist, so `seenIds` is an incomplete view of the
                // library. The tombstone sweep is authoritative only for a fully-applied
                // Full scan — sweeping on a partial set would soft-delete a book that is
                // present on disk but transiently failed. Skip it; the next clean Full
                // scan reconciles genuine removals. Losing a present book is unacceptable;
                // deferring a tombstone is not.
                log.warn {
                    "Skipping tombstone sweep for library ${libraryId.value}: " +
                        "${result.books.size} scanned, some failed to persist"
                }
            } else {
                ingest.softDeleteAbsent(libraryId, seenIds)
            }
        }
    }

    /**
     * Persists one scanned book, contained against its own failure: a typed
     * failure or an escaped exception is logged and counted, never aborting the
     * rest of the scan. Returns the resolved [BookId] when the aggregate landed
     * (so the caller can track it for the tombstone sweep), or null otherwise.
     *
     * Cover bytes are extracted from [analyzed] BEFORE calling [BookIngestPort.resolveOrInsert]
     * so the file read happens outside the DB transaction. The [PendingCover] is passed
     * to [BookIngestPort.resolveOrInsert], which stores the file to the managed cover
     * store after the stable [BookId] is known.
     */
    private suspend fun persistOne(
        analyzed: AnalyzedBook,
        libraryId: LibraryId,
        folderId: FolderId,
        scanRoot: JPath,
    ): BookId? =
        try {
            val pending = extractPendingCover(analyzed, scanRoot)
            when (val r = ingest.resolveOrInsert(libraryId, folderId, analyzed, pending)) {
                is AppResult.Success -> {
                    r.data.bookId
                }

                is AppResult.Failure -> {
                    log.warn { "Book persist failed: ${analyzed.candidate.rootRelPath} — ${r.error.code}; continuing" }
                    metrics.bookPersistFailures.increment()
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.warn(e) { "Book persist threw: ${analyzed.candidate.rootRelPath} — continuing" }
            metrics.bookPersistFailures.increment()
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
        suspendTransaction(db) {
            LibraryFolderTable
                .selectAll()
                .where { LibraryFolderTable.rootPath eq rootPath and LibraryFolderTable.deletedAt.isNull() }
                .firstOrNull()
                ?.let { FolderId(it[LibraryFolderTable.id]) }
        } ?: run {
            log.warn { "No library_folder row found for rootPath='$rootPath' — book folderId will be unknown" }
            FolderId("unknown")
        }
}
