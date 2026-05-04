package com.calypsan.listenup.api.event

import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-to-client streaming events emitted during and after a scan. Every
 * event carries `correlationId` so a client can disambiguate concurrent
 * server-side scans (a watcher-driven incremental can run after a manual
 * full scan ends — the IDs differentiate the streams).
 *
 * Throughput: `Progress` is throttled at the scanner to at most one emit
 * per ~200ms; `Change` and `Started`/`Completed` are emitted at their
 * natural rate.
 */
@Serializable
sealed interface ScanEvent {
    val correlationId: String

    @Serializable
    @SerialName("started")
    data class Started(
        override val correlationId: String,
        val rootPath: String,
    ) : ScanEvent

    @Serializable
    @SerialName("progress")
    data class Progress(
        override val correlationId: String,
        val phase: ScanPhase,
        val filesWalked: Int,
        val booksAnalyzed: Int,
        val errors: Int,
    ) : ScanEvent

    @Serializable
    @SerialName("change")
    data class Change(
        override val correlationId: String,
        val event: ChangeEventDto,
    ) : ScanEvent

    @Serializable
    @SerialName("completed")
    data class Completed(
        override val correlationId: String,
        val result: ScanResultSummary,
    ) : ScanEvent
}
