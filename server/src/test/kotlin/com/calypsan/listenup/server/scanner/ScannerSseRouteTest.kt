package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the `GET /sse/scan` route specifically. Two contract concerns:
 *
 *  - **Frame format.** Each SSE frame's `data` field deserializes back to
 *    a [ScanEvent] via the polymorphic `@SerialName` discriminator — no
 *    silent type erosion.
 *  - **Live stream.** A scan triggered via REST while a client is
 *    subscribed produces the `Started → … → Completed` sequence on the
 *    SSE stream.
 */
class ScannerSseRouteTest :
    FunSpec({

        test("SSE stream emits Started and Completed for a manual scan") {
            // Real wall-clock — the SSE client runs on the Default dispatcher
            // and uses real time; runTest's virtual time would dispatch
            // `delay()` calls in the test body without actually waiting,
            // which races the SSE subscription against the scan.
            runBlocking {
                val fix =
                    autoClose(
                        ScannerEndToEndFixture.start {
                            book("Author/Title") { tracks(count = 1) }
                        },
                    )

                // Wait until the library is registered with the orchestrator
                // (bootstrap is async). Without this, the POST /scan below
                // may return LibraryNotFound before the orchestrator is ready.
                waitForLibraryReady(fix)

                // Subscribe to the SSE stream, collect events as they arrive.
                val collected = mutableListOf<ScanEvent>()
                // Completes the instant the SSE block runs — i.e. the connection is established and the
                // handler is about to collect the (replay = 0) event stream. Awaiting this instead of a
                // blind delay removes the race that dropped the Started frame when the subscription
                // wasn't yet registered on a loaded CI runner.
                val subscribed = CompletableDeferred<Unit>()
                val sseJob =
                    async {
                        withTimeoutOrNull(10.seconds) {
                            fix.client.sse("${fix.baseUrl}/sse/scan") {
                                subscribed.complete(Unit)
                                incoming
                                    .transformWhile { frame ->
                                        val data = frame.data
                                        if (data != null) {
                                            val event =
                                                runCatching {
                                                    contractJson.decodeFromString(ScanEvent.serializer(), data)
                                                }.getOrNull()
                                            if (event != null) {
                                                emit(event)
                                                if (event is ScanEvent.Completed) return@transformWhile false
                                            }
                                        }
                                        true
                                    }.collect { collected.add(it) }
                            }
                        }
                    }

                // Wait for the SSE connection to actually be established — bounded so a failed connect
                // surfaces as a failed assertion rather than a hang — then a brief settle for the
                // server-side collector to register before the scan fires.
                withTimeoutOrNull(10.seconds) { subscribed.await() }
                delay(200)

                // Trigger a manual scan to generate fresh events.
                fix.client.post("${fix.baseUrl}/api/v1/scan")

                sseJob.await()

                collected.size shouldBeGreaterThan 0
                collected.any { it is ScanEvent.Started } shouldBe true
                collected.any { it is ScanEvent.Completed } shouldBe true
                // Polymorphic discriminator survived — every event has a
                // non-empty correlationId.
                collected.forEach { it.correlationId.isNotEmpty() shouldBe true }
            }
        }
    })

/**
 * Triggers a scan and waits for it to complete so the orchestrator is warm
 * and no scan is in progress. This replaces the old `waitForBootstrap` which
 * polled `GET /scan/last` waiting for an auto-scan that no longer happens
 * (Task 18: no auto-scan on boot).
 *
 * On return: library is registered with the orchestrator, no scan in flight.
 */
private suspend fun waitForLibraryReady(
    fix: ScannerEndToEndFixture,
    timeoutMs: Long = 10_000,
) {
    val deadline = System.currentTimeMillis() + timeoutMs

    // Step 1: POST /scan until the library is registered (Success = scan started).
    // Guard against non-200 responses (e.g. 404 during server startup races on CI).
    while (System.currentTimeMillis() < deadline) {
        val response = fix.client.post("${fix.baseUrl}/api/v1/scan")
        if (response.status == HttpStatusCode.OK) {
            val body: AppResult<ScanResultSummary> =
                contractJson.decodeFromString(
                    AppResult.serializer(ScanResultSummary.serializer()),
                    response.body<String>(),
                )
            if (body is AppResult.Success) break
        }
        delay(50)
    }

    // Step 2: Wait for that scan to complete (no scan in progress when SSE test fires).
    while (System.currentTimeMillis() < deadline) {
        val body: AppResult<ScanResult> =
            contractJson.decodeFromString(
                AppResult.serializer(ScanResult.serializer()),
                fix.client.get("${fix.baseUrl}/api/v1/scan/last").body<String>(),
            )
        if (body is AppResult.Success) return
        delay(50)
    }
    error("library not ready within ${timeoutMs}ms")
}
