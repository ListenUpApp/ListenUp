package com.calypsan.listenup.server.rpcguard

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Proves the KSP-generated streaming guard wires the C2 session-liveness gate end-to-end: a revoked
 * session's live `observeProgress` stream is severed with a terminal `RpcEvent.Error(SessionExpired)`
 * within the poll window, while a live session streams untouched. Exercised through the production
 * `guard(impl, sessionLiveness)` overload, on the virtual clock so it isn't wall-time slow.
 */
class RpcGuardLivenessTest :
    FunSpec({

        test("a revoked session severs the stream with SessionExpired within the poll window") {
            runTest {
                var alive = true
                // A long-lived progress stream that never completes on its own.
                val guarded = guard(HangingScannerService()) { alive }

                val received = mutableListOf<RpcEvent<ScanEvent>>()
                val job = launch { guarded.observeProgress().collect { received.add(it) } }

                // Before the session dies, the stream stays open with nothing terminal.
                runCurrent()
                received.shouldBe(emptyList())

                // Session revoked → the next poll (≤ the production poll window) severs it.
                alive = false
                advanceTimeBy(PRODUCTION_POLL_MILLIS + 1)
                runCurrent()
                job.join()

                received.size shouldBe 1
                val error = received.single().shouldBeInstanceOf<RpcEvent.Error>()
                error.error.shouldBeInstanceOf<AuthError.SessionExpired>()
            }
        }

        test("a live session's stream runs to completion past several poll windows, unsevered") {
            runTest {
                val guarded = guard(FiniteScannerService()) { true }

                // Completes on its own after 3 poll windows; the gate must neither sever it nor
                // inject a spurious Error while the session stays live.
                val received = collectAll(guarded.observeProgress())

                received.shouldBe(emptyList())
            }
        }
    })

// Mirrors the ~30s poll the generated guard inlines (a file-private const per streaming guard, not a
// shared symbol). A literal keeps this test decoupled from generated internals.
private const val PRODUCTION_POLL_MILLIS = 30_000L

/** Emits nothing and hangs forever — models a live progress/firehose stream awaiting events. */
private class HangingScannerService : ScannerService {
    override suspend fun scanFull(): AppResult<ScanResultSummary> = error("unused")

    override suspend fun lastScanResult(): AppResult<ScanResult> = error("unused")

    override fun observeProgress(): Flow<RpcEvent<ScanEvent>> = flow { awaitCancellation() }
}

/** Runs past several poll windows then completes normally, emitting nothing — the gate must not interfere. */
private class FiniteScannerService : ScannerService {
    override suspend fun scanFull(): AppResult<ScanResultSummary> = error("unused")

    override suspend fun lastScanResult(): AppResult<ScanResult> = error("unused")

    override fun observeProgress(): Flow<RpcEvent<ScanEvent>> =
        flow {
            kotlinx.coroutines.delay(PRODUCTION_POLL_MILLIS * 3) // spans several poll windows
        }
}

private suspend fun <T> collectAll(flow: Flow<T>): List<T> =
    buildList {
        flow.collect { add(it) }
    }
