package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.core.LibraryId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Thin [ScannerService] implementation. The work lives in [ScanOrchestrator];
 * the orchestrator's per-library [ScanCoordinator] enforces single-flight;
 * this class just translates between the wire contract and the internal types.
 *
 * [resolveLibraryId] is a suspend function that returns the single library id
 * for this process. It is called on every `scanFull` and `lastScanResult` call
 * so the implementation stays correct even when the id is resolved lazily
 * (e.g. from [com.calypsan.listenup.server.services.LibraryRegistry]). The
 * result is stable for the process lifetime so the underlying query is cheap
 * after the first call (LibraryRegistry caches it).
 *
 * `lastScanResult()` returns [ScanError.LibraryPathNotConfigured] when no
 * scan has run yet — a slightly stretched semantic, but the existing
 * [ScanError] hierarchy doesn't have a "no scan yet" variant and adding
 * one purely for this call site is overkill. Callers checking
 * `lastScanResult` already have to handle the configuration failure mode;
 * lumping "not yet" in is acceptable.
 */
internal class ScannerServiceImpl(
    private val orchestrator: ScanOrchestrator,
    private val resolveLibraryId: suspend () -> LibraryId,
    private val eventBus: SharedFlow<ScanEvent>,
) : ScannerService {
    override suspend fun scanFull(): AppResult<ScanResultSummary> =
        orchestrator.scanLibrary(resolveLibraryId()).map { it.toSummary() }

    override suspend fun lastScanResult(): AppResult<ScanResult> =
        orchestrator.lastResult(resolveLibraryId())?.let { AppResult.Success(it) }
            ?: AppResult.Failure(ScanError.LibraryPathNotConfigured())

    override fun observeProgress(libraryId: LibraryId?): Flow<RpcEvent<ScanEvent>> =
        eventBus
            .let { bus -> if (libraryId != null) bus.filter { it.libraryId == libraryId } else bus }
            // Progress monitoring needs only the lightweight lifecycle/progress events. Each
            // [ScanEvent.Change] carries a full [AnalyzedBook] (metadata + artwork) — a 1000-book
            // scan would stream multi-MB frames and OOM a subscriber that just wants progress.
            // Per-book changes reach clients via the sync substrate (firehose + catch-up), not here.
            .filter { it !is ScanEvent.Change }
            .map { RpcEvent.Data(it) }
}
