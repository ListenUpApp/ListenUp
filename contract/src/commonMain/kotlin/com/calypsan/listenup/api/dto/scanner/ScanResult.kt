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
 * The aggregated output of one scan invocation. Phase 2 keeps this purely
 * in-memory — no persistence yet. Phase 4's Books domain takes this as its
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
 * Lightweight version of [ScanResult] returned by `scanFull()` over RPC and
 * embedded in completion SSE events. The full books list is fetchable via
 * `lastScanResult()` when needed — keeping it out of progress events keeps
 * the wire small.
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
    val embedded: EmbeddedScanCounters = EmbeddedScanCounters(),
)
