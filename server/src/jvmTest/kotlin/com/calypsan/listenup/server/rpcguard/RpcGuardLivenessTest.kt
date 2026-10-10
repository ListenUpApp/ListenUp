package com.calypsan.listenup.server.rpcguard

import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
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

        test("a live session's ordinary call reaches the service") {
            runTest {
                val service = CountingScannerService()
                val guarded = guard(service) { true }

                guarded.scanFull().shouldBeInstanceOf<AppResult.Success<ScanResultSummary>>()
                service.scanFullCalls shouldBe 1
            }
        }

        test("a revoked session answers an ordinary call with SessionExpired and never reaches the service") {
            runTest {
                val service = CountingScannerService()
                var alive = true
                val guarded = guard(service) { alive }
                guarded.scanFull()

                alive = false
                val failure = guarded.scanFull().shouldBeInstanceOf<AppResult.Failure>()

                failure.error.shouldBeInstanceOf<AuthError.SessionExpired>()
                failure.error.correlationId.shouldNotBeNull()
                service.scanFullCalls shouldBe 1
            }
        }

        test("an unarmed guard never blocks an ordinary call") {
            runTest {
                val guarded = guard(CountingScannerService())

                guarded.scanFull().shouldBeInstanceOf<AppResult.Success<ScanResultSummary>>()
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

    override suspend fun listScanIssues() = AppResult.Success(emptyList<com.calypsan.listenup.api.dto.scan.ScanIssue>())

    override suspend fun dismissScanIssue(issueId: String) = AppResult.Success(Unit)
}

/** Answers `scanFull` with a success and counts how many ordinary calls got past the guard. */
private class CountingScannerService : ScannerService {
    var scanFullCalls = 0
        private set

    override suspend fun scanFull(): AppResult<ScanResultSummary> {
        scanFullCalls++
        return AppResult.Success(
            ScanResultSummary(
                correlationId = "scan",
                totalBooks = 0,
                added = 0,
                modified = 0,
                removed = 0,
                moved = 0,
                errors = 0,
                durationMs = 0,
                filesWalked = 0,
            ),
        )
    }

    override suspend fun lastScanResult(): AppResult<ScanResult> = error("unused")

    override fun observeProgress(): Flow<RpcEvent<ScanEvent>> = flow { awaitCancellation() }

    override suspend fun listScanIssues() = AppResult.Success(emptyList<com.calypsan.listenup.api.dto.scan.ScanIssue>())

    override suspend fun dismissScanIssue(issueId: String) = AppResult.Success(Unit)
}

/** Runs past several poll windows then completes normally, emitting nothing — the gate must not interfere. */
private class FiniteScannerService : ScannerService {
    override suspend fun scanFull(): AppResult<ScanResultSummary> = error("unused")

    override suspend fun lastScanResult(): AppResult<ScanResult> = error("unused")

    override fun observeProgress(): Flow<RpcEvent<ScanEvent>> =
        flow {
            kotlinx.coroutines.delay(PRODUCTION_POLL_MILLIS * 3) // spans several poll windows
        }

    override suspend fun listScanIssues() = AppResult.Success(emptyList<com.calypsan.listenup.api.dto.scan.ScanIssue>())

    override suspend fun dismissScanIssue(issueId: String) = AppResult.Success(Unit)
}

private suspend fun <T> collectAll(flow: Flow<T>): List<T> =
    buildList {
        flow.collect { add(it) }
    }
