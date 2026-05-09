package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.streaming.RpcEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map

/**
 * Thin [ScannerService] implementation. The work lives in [Scanner];
 * the coordinator enforces single-flight; this class just translates
 * between the wire contract and the internal types.
 *
 * `lastScanResult()` returns [ScanError.LibraryPathNotConfigured] when no
 * scan has run yet — a slightly stretched semantic, but the existing
 * [ScanError] hierarchy doesn't have a "no scan yet" variant and adding
 * one purely for this call site is overkill. Callers checking
 * `lastScanResult` already have to handle the configuration failure mode;
 * lumping "not yet" in is acceptable.
 */
internal class ScannerServiceImpl(
    private val scanner: Scanner,
    private val coordinator: ScanCoordinator,
    private val eventBus: SharedFlow<ScanEvent>,
) : ScannerService {
    override suspend fun scanFull(): AppResult<ScanResultSummary> = coordinator.scanFull().map { it.toSummary() }

    override suspend fun lastScanResult(): AppResult<ScanResult> =
        scanner.lastResult()?.let { AppResult.Success(it) }
            ?: AppResult.Failure(ScanError.LibraryPathNotConfigured())

    override fun observeProgress(): Flow<RpcEvent<ScanEvent>> = eventBus.map { RpcEvent.Data(it) }
}
