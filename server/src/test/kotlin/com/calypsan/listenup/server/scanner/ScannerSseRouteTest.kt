package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
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

                // Wait for the bootstrap scan to finish so we have a stable
                // mutex state — otherwise the manual scan we trigger below
                // could race against bootstrap and return AlreadyRunning,
                // generating no events for the SSE stream.
                waitForBootstrap(fix)

                // Subscribe to the SSE stream, collect events as they arrive.
                val collected = mutableListOf<ScanEvent>()
                val sseJob =
                    async {
                        withTimeoutOrNull(10.seconds) {
                            fix.client.sse("${fix.baseUrl}/sse/scan") {
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

                // Pause so the SSE subscription is established before the scan
                // fires — otherwise we miss the Started frame.
                delay(500)

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

private suspend fun waitForBootstrap(fix: ScannerEndToEndFixture) {
    val deadline = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < deadline) {
        val response = fix.client.get("${fix.baseUrl}/api/v1/scan/last")
        val body: AppResult<ScanResult> =
            contractJson.decodeFromString(
                AppResult.serializer(ScanResult.serializer()),
                response.body<String>(),
            )
        if (body is AppResult.Success) return
        delay(50)
    }
    error("bootstrap scan did not complete within 10s")
}

@Suppress("unused")
private suspend fun HttpResponse.discard() {
    body<String>()
}
