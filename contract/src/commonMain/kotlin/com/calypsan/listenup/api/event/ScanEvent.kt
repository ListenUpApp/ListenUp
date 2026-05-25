package com.calypsan.listenup.api.event

import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.core.LibraryId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-to-client streaming events emitted during and after a scan. Every
 * event carries `correlationId` so a client can disambiguate concurrent
 * server-side scans (a watcher-driven incremental can run after a manual
 * full scan ends — the IDs differentiate the streams), and `libraryId` so a
 * multi-library client can route events to the correct library's view without
 * parsing the correlationId.
 *
 * Throughput: `Progress` is throttled at the scanner to at most one emit
 * per ~200ms; `Change` and `Started`/`Completed` are emitted at their
 * natural rate.
 */
@Serializable
sealed interface ScanEvent {
    val correlationId: String
    val libraryId: LibraryId

    /** Scan has begun. Emitted once per [correlationId]; carries the root path being scanned. */
    @Serializable
    @SerialName("started")
    data class Started(
        override val correlationId: String,
        @SerialName("libraryId") override val libraryId: LibraryId,
        val rootPath: String,
    ) : ScanEvent

    /**
     * Periodic progress tick. Throttled at the scanner to at most one emit per ~200ms; clients
     * use this to drive UI counters without redrawing on every file processed.
     */
    @Serializable
    @SerialName("progress")
    data class Progress(
        override val correlationId: String,
        @SerialName("libraryId") override val libraryId: LibraryId,
        val phase: ScanPhase,
        val filesWalked: Int,
        val booksAnalyzed: Int,
        val errors: Int,
    ) : ScanEvent

    /**
     * One Differ output (added/modified/removed/moved book) emitted as soon as the scanner
     * categorises it. The client applies these to its local index incrementally.
     */
    @Serializable
    @SerialName("change")
    data class Change(
        override val correlationId: String,
        @SerialName("libraryId") override val libraryId: LibraryId,
        val event: ChangeEventDto,
    ) : ScanEvent

    /** Terminal event for [correlationId]. Carries the aggregate summary; no further events follow. */
    @Serializable
    @SerialName("completed")
    data class Completed(
        override val correlationId: String,
        @SerialName("libraryId") override val libraryId: LibraryId,
        val result: ScanResultSummary,
    ) : ScanEvent
}
