package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.db.LibraryFolderTable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
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
 * library, so books absent from it are soft-deleted. A [ScanScope.Subtree]
 * result only re-walked one book-root — absence there is not authoritative, so
 * no sweep runs.
 */
class BookPersister(
    private val ingest: BookIngestPort,
    private val libraryRegistry: LibraryRegistry,
    private val db: Database,
    private val scanResultBus: SharedFlow<ScanResult>,
    private val scope: CoroutineScope,
    private val metrics: BookPersisterMetrics,
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
        val seenIds = mutableSetOf<BookId>()
        for (analyzed in result.books) {
            try {
                when (val r = ingest.resolveOrInsert(libraryId, folderId, analyzed)) {
                    is AppResult.Success -> {
                        seenIds += r.data
                    }

                    is AppResult.Failure -> {
                        log.warn {
                            "Book persist failed: ${analyzed.candidate.rootRelPath} — ${r.error.code}; continuing"
                        }
                        metrics.bookPersistFailures.increment()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn(e) { "Book persist threw: ${analyzed.candidate.rootRelPath} — continuing" }
                metrics.bookPersistFailures.increment()
            }
        }
        if (result.scope is ScanScope.Full) {
            ingest.softDeleteAbsent(libraryId, seenIds)
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
