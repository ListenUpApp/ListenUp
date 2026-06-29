package com.calypsan.listenup.api.dto.scanner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Discriminates a [ScanResult] by the breadth of the scan that produced it.
 *
 * `BookPersister` uses this to decide whether to run the absent-book tombstone
 * sweep: a [Full] scan is authoritative for the whole library (sweep absent
 * books), a [Subtree] scan only re-walked one book-root (a book missing from a
 * subtree may just mean the user paused scanning — never sweep).
 */
@Serializable
sealed interface ScanScope {
    /** A full library walk — authoritative for absence. */
    @Serializable
    @SerialName("ScanScope.Full")
    data object Full : ScanScope

    /** An incremental walk of one book-root subtree, identified by its library-relative path. */
    @Serializable
    @SerialName("ScanScope.Subtree")
    data class Subtree(
        val rootRelPath: String,
    ) : ScanScope
}

/**
 * The aggregated output of one scan invocation. Kept purely
 * in-memory — no persistence. The Books domain takes this as its
 * input.
 */
@Serializable
data class ScanResult(
    @SerialName("correlationId")
    val correlationId: String,
    val rootPath: String,
    val books: List<AnalyzedBook>,
    val changes: List<ChangeEventDto>,
    val errors: List<com.calypsan.listenup.api.error.ScanError>,
    val durationMs: Long,
    val filesWalked: Int,
    val filesSkipped: Int,
    val scope: ScanScope = ScanScope.Full,
)

/**
 * Returns a copy where every [AnalyzedBook] in both [ScanResult.books] and [ScanResult.changes]
 * has artwork stripped via [AnalyzedBook.withoutArtwork].
 *
 * Used by [com.calypsan.listenup.server.scanner.Scanner] to build `lastResult`: the bus delivers
 * the artwork-bearing result to `BookPersister` so covers are written to disk; `lastResult` stores
 * the stripped copy so artwork bytes do not accumulate in heap across scans.
 */
fun ScanResult.withoutArtwork(): ScanResult =
    copy(
        books = books.map { it.withoutArtwork() },
        changes =
            changes.map { change ->
                when (change) {
                    is ChangeEventDto.Added -> change.copy(book = change.book.withoutArtwork())
                    is ChangeEventDto.Modified -> change.copy(book = change.book.withoutArtwork())
                    is ChangeEventDto.Moved -> change.copy(book = change.book.withoutArtwork())
                    is ChangeEventDto.Removed -> change
                }
            },
    )

/**
 * Lightweight version of [ScanResult] returned by `scanFull()` over RPC and
 * embedded in completion SSE events. The full books list is fetchable via
 * `lastScanResult()` when needed — keeping it out of progress events keeps
 * the wire small.
 *
 * [persisted] and [failed] are set by [com.calypsan.listenup.server.services.BookPersister]
 * after each book is committed (or fails) — so `persisted + failed == totalBooks` when the
 * scan completes normally, and `persisted + failed < totalBooks` only when an
 * [OutOfMemoryError] forces an early stop. A clean run has `failed == 0`; any non-zero
 * value signals a partial ingest so clients can prompt a re-scan rather than silently
 * accepting an incomplete library.
 */
@Serializable
data class ScanResultSummary(
    @SerialName("correlationId")
    val correlationId: String,
    val totalBooks: Int,
    val added: Int,
    val modified: Int,
    val removed: Int,
    val moved: Int,
    val errors: Int,
    val durationMs: Long,
    val filesWalked: Int,
    /** Books successfully committed to the database during this scan. */
    val persisted: Int = 0,
    /** Books that failed to persist (typed failure or escaped exception). */
    val failed: Int = 0,
    val embedded: EmbeddedScanCounters = EmbeddedScanCounters(),
)
