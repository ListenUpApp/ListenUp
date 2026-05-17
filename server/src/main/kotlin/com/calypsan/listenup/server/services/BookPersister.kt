package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

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
        val seenIds = mutableSetOf<BookId>()
        for (analyzed in result.books) {
            try {
                when (val r = ingest.resolveOrInsert(libraryId, analyzed)) {
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
}
