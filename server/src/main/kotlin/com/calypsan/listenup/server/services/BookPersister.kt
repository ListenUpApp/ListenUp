package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
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
 *
 * Inbox auto-add: when the library's `inbox_enabled` flag is set, a genuinely
 * NEW book (not a rescan or move) is added to the library's inbox via
 * [InboxIngest] so it stays hidden from members pending admin triage. Rescans
 * are never re-inboxed — a released book stays released.
 */
class BookPersister(
    private val ingest: BookIngestPort,
    private val libraryRegistry: LibraryRegistry,
    private val db: Database,
    private val scanResultBus: SharedFlow<ScanResult>,
    private val scope: CoroutineScope,
    private val metrics: BookPersisterMetrics,
    private val inboxIngest: InboxIngest,
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
        val inboxEnabled = isInboxEnabled(libraryId)
        val seenIds = mutableSetOf<BookId>()
        for (analyzed in result.books) {
            persistOne(analyzed, libraryId, folderId, inboxEnabled)?.let { seenIds += it }
        }
        if (result.scope is ScanScope.Full) {
            ingest.softDeleteAbsent(libraryId, seenIds)
        }
    }

    /**
     * Persists one scanned book, contained against its own failure: a typed
     * failure or an escaped exception is logged and counted, never aborting the
     * rest of the scan. Returns the resolved [BookId] when the aggregate landed
     * (so the caller can track it for the tombstone sweep), or null otherwise.
     *
     * When the library's inbox is [inboxEnabled] and this was a genuinely NEW
     * book, it is added to the inbox; rescans and moves are never re-inboxed.
     */
    private suspend fun persistOne(
        analyzed: AnalyzedBook,
        libraryId: LibraryId,
        folderId: FolderId,
        inboxEnabled: Boolean,
    ): BookId? =
        try {
            when (val r = ingest.resolveOrInsert(libraryId, folderId, analyzed)) {
                is AppResult.Success -> {
                    if (inboxEnabled && r.data.wasNew) addToInbox(r.data.bookId, libraryId)
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

    /** Reads the library's `inbox_enabled` gate. A missing row reads as disabled. */
    private suspend fun isInboxEnabled(libraryId: LibraryId): Boolean =
        suspendTransaction(db) {
            LibraryTable
                .selectAll()
                .where { LibraryTable.id eq libraryId.value }
                .firstOrNull()
                ?.get(LibraryTable.inboxEnabled)
                ?: false
        }

    /**
     * Adds a newly-scanned [bookId] to [libraryId]'s inbox. A failure here is
     * contained and logged — a triage-routing miss must never abort the scan or
     * fail the book's persistence, which already landed.
     */
    private suspend fun addToInbox(
        bookId: BookId,
        libraryId: LibraryId,
    ) {
        val result = inboxIngest.addToInbox(bookId.value, libraryId.value)
        if (result is AppResult.Failure) {
            log.warn { "Inbox add failed for book ${bookId.value}: ${result.error.code}; book persisted, continuing" }
        }
    }
}
