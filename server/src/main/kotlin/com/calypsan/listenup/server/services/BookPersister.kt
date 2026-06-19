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
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.db.LibraryFolderTable
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
 * Inbox auto-quarantine: when the current library is `inboxEnabled`, the persister
 * resolves the library's inbox collection ONCE per scan (via
 * [com.calypsan.listenup.server.api.CollectionServiceImpl.getOrCreateInbox]) and
 * threads its id into every per-book ingest. [BookIngestPort.resolveOrInsert]
 * commits the book→inbox membership inside the very same transaction as a genuinely
 * NEW book row, so the firehose — which evaluates
 * [com.calypsan.listenup.server.api.BookAccessPolicy.canAccess] at delivery — never
 * exposes the book to members (it is already in the admin-only inbox before the
 * `book.Created` publish is observable). This closes the TOCTOU leak that the old
 * separate-transaction `addToInbox` hook carried. Re-scans/updates of an existing
 * book never re-inbox it. Resolving the inbox must never fail the scan: a
 * [getOrCreateInbox] failure logs a warning and falls back to null, leaving the
 * scanned books uncollected (offline-safe ingest).
 *
 * [coverImageStore] is optional: when non-null, the persister extracts cover
 * bytes from each [AnalyzedBook] (filesystem or embedded) and passes a
 * [PendingCover] to [BookIngestPort.resolveOrInsert] so the repository can
 * write the managed cover file after the book id is known. When null (e.g. in
 * pure orchestration tests), cover extraction is skipped.
 *
 * [libraryRepository] reads the per-library `inboxEnabled` gate; [collectionService]
 * resolves (or creates) the library's inbox collection when that gate is on. Both
 * back the inbox auto-quarantine described above.
 */
class BookPersister internal constructor(
    private val ingest: BookIngestPort,
    private val libraryRegistry: LibraryRegistry,
    private val libraryRepository: LibraryRepository,
    private val collectionService: CollectionServiceImpl,
    private val db: Database,
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

            // A FULL scan is bulk population (onboarding, re-scan): suppress the per-book firehose
            // PUBLISH so the lossy live tail (ChangeBus replay=256, DROP_OLDEST) never carries the
            // burst — an arbitrarily large library would otherwise overflow it and trip a client
            // CursorStale → catch-up spin. Revisions still bump, so the client does one clean REST
            // catch-up after the Completed below. Incremental scans ARE live deltas; they publish.
            val counts: PersistCounts
            try {
                counts =
                    if (result.scope is ScanScope.Full) {
                        withContext(FirehoseSuppressed) { persistAll(result, libraryId, folderId) }
                    } else {
                        persistAll(result, libraryId, folderId)
                    }
            } catch (e: OutOfMemoryError) {
                // OOM means the JVM heap is compromised. We still emit Completed with the partial
                // counts gathered so far (stored in the thrown PersistAbortedByOom) so clients get
                // honest numbers, then rethrow so the process can surface the failure.
                val partial = (e as? PersistAbortedByOom)?.counts ?: PersistCounts(0, 0)
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
     * Persists every book in [result] and, for a [ScanScope.Full] result, runs the tombstone
     * sweep. The caller decides whether this runs under [FirehoseSuppressed]; this method is
     * suppression-agnostic.
     *
     * Returns [PersistCounts] summarising how many books landed vs failed. On [OutOfMemoryError]
     * the loop is stopped immediately and a [PersistAbortedByOom] wraps both the partial counts
     * and the original error so the caller can emit an honest [ScanEvent.Completed] before
     * rethrowing — OOM signals a compromised heap and must never be swallowed per-book.
     */
    private suspend fun persistAll(
        result: ScanResult,
        libraryId: LibraryId,
        folderId: FolderId,
    ): PersistCounts {
        val seenIds = mutableSetOf<BookId>()
        // Use the scan result's rootPath for filesystem cover reads — aligned
        // with Analyzer's own path resolution (Analyzer.kt: rootPath.resolve(relPath)).
        val scanRoot = JPath.of(result.rootPath)
        // Resolve the library's inbox ONCE per scan when the gate is on, so every
        // newly-inserted book quarantines into it atomically (see class KDoc). A
        // resolution failure must never fail the scan — fall back to null (uncollected).
        val inboxCollectionId = resolveInboxCollectionId(libraryId)
        var persisted = 0
        var failed = 0
        for (analyzed in result.books) {
            val bookId: BookId?
            try {
                bookId = persistOne(analyzed, libraryId, folderId, scanRoot, inboxCollectionId)
            } catch (e: OutOfMemoryError) {
                // OOM means the heap is compromised — stop immediately. Wrap partial counts so
                // the caller can still emit an honest Completed before rethrowing.
                failed++
                throw PersistAbortedByOom(PersistCounts(persisted, failed), e)
            }
            if (bookId != null) {
                seenIds += bookId
                persisted++
            } else {
                failed++
            }
        }
        if (result.scope is ScanScope.Full) {
            if (failed > 0) {
                // A book failed to persist, so `seenIds` is an incomplete view of the
                // library. The tombstone sweep is authoritative only for a fully-applied
                // Full scan — sweeping on a partial set would soft-delete a book that is
                // present on disk but transiently failed. Skip it; the next clean Full
                // scan reconciles genuine removals. Losing a present book is unacceptable;
                // deferring a tombstone is not.
                log.warn {
                    "Skipping tombstone sweep for library ${libraryId.value}: " +
                        "$persisted persisted, $failed failed out of ${result.books.size} scanned"
                }
            } else {
                ingest.softDeleteAbsent(libraryId, seenIds)
            }
        }
        return PersistCounts(persisted, failed)
    }

    /**
     * Resolves the library's inbox collection id when [libraryId] is `inboxEnabled`,
     * or null when the gate is off or the inbox cannot be resolved.
     *
     * A [CollectionServiceImpl.getOrCreateInbox] failure (e.g. no admin to own the
     * inbox yet) must not fail the scan — it logs a warning and returns null, so the
     * scanned books land uncollected rather than stranding a half-ingested library.
     */
    private suspend fun resolveInboxCollectionId(libraryId: LibraryId): String? {
        // Resolving the inbox must never fail the scan: a typed Failure OR an escaped DB/connection
        // fault degrades to "no quarantine" (books stay uncollected) rather than killing the scan
        // consumer coroutine. CancellationException is always re-raised.
        return try {
            if (!libraryRepository.readInboxEnabled(libraryId)) {
                return null
            }
            when (val result = collectionService.getOrCreateInbox(libraryId.value)) {
                is AppResult.Success -> {
                    result.data.id.value
                }

                is AppResult.Failure -> {
                    log.warn { "Inbox quarantine skipped for library ${libraryId.value}: ${result.error.code}" }
                    null
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "Inbox resolution failed for library ${libraryId.value}; scanning without quarantine" }
            null
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
        inboxCollectionId: String?,
    ): BookId? =
        try {
            val pending = extractPendingCover(analyzed, scanRoot)
            when (val r = ingest.resolveOrInsert(libraryId, folderId, analyzed, pending, inboxCollectionId)) {
                is AppResult.Success -> {
                    r.data.bookId
                }

                is AppResult.Failure -> {
                    log.warn { "Book persist failed: ${analyzed.candidate.rootRelPath} — ${r.error.code}; continuing" }
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            // Propagate — heap is compromised; persistAll must stop the loop.
            throw e
        } catch (e: Throwable) {
            log.warn(e) { "Book persist threw: ${analyzed.candidate.rootRelPath} — continuing" }
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

/**
 * Thrown by [BookPersister.persistAll] when an [OutOfMemoryError] forces an early stop.
 * Wraps the partial [counts] accumulated before the OOM so the caller can emit an honest
 * [ScanEvent.Completed] before rethrowing the underlying [OutOfMemoryError].
 */
internal class PersistAbortedByOom(
    val counts: PersistCounts,
    cause: OutOfMemoryError,
) : OutOfMemoryError(cause.message)
