package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * Public scanner contract. Mounted at `/api/rpc/public` (no auth wall
 * currently; admin-gating lands when the auth surface gets a role check).
 *
 * `observeProgress()` is a server-pushed [Flow] of [RpcEvent]-wrapped [ScanEvent]s —
 * kotlinx.rpc opens a dedicated WebSocket frame stream for it. Multiple
 * clients can subscribe simultaneously and they all see the same events
 * (the scanner's event bus is a broadcast `SharedFlow`). Emits
 * [RpcEvent.Data] for each underlying event; the guard wraps any internal
 * failures as [RpcEvent.Error].
 *
 *  - `scanFull()` returns immediately with `Failure(AlreadyRunning)` if a
 *    scan is in flight.
 *  - `lastScanResult()` returns the most-recent completed scan's full
 *    result — including the books list — for read-after-scan flows that
 *    don't want to subscribe.
 *  - `observeProgress()` receives events from THE library (single-library model).
 */
@Rpc
interface ScannerService {
    suspend fun scanFull(): AppResult<ScanResultSummary>

    suspend fun lastScanResult(): AppResult<ScanResult>

    fun observeProgress(): Flow<RpcEvent<ScanEvent>>
}
