@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * [SyncSseClient.apply] is driven directly rather than through the real [SseConnection] engine —
 * forcing a deterministic mid-emit suspension on `frameBus.emit` through the real connect() path
 * would need a sleepy wall-clock race (there is no other seam that reliably stalls a specific
 * emit). `apply` is `internal` for exactly this reason; see its KDoc.
 */
class SyncSseClientDeliveryOrderingTest :
    FunSpec({
        test("lastEventId does not advance past a frame whose delivery never completed") {
            val scope = TestScope(StandardTestDispatcher())
            val state = SyncEngineState()
            val client =
                SyncSseClient(
                    serverUrlProvider = { null },
                    streamingClientProvider = { error("unused — apply() is driven directly, not via connect()") },
                    state = state,
                    scope = scope,
                    nowMillis = { 0L },
                    // Zero buffer: the second frame's emit has nowhere to land once the one
                    // collector is stalled processing the first, so it suspends deterministically.
                    frameBufferCapacity = 0,
                )

            // Consumes exactly one frame, then never returns to ask for the next — this is what a
            // downstream consumer that stops draining (or a slow one) looks like from frameBus's side.
            val collectorJob = scope.launch { client.frames.collect { awaitCancellation() } }
            scope.testScheduler.runCurrent()

            val frame1 = ParsedSseFrame(id = 1L, event = "tags", data = "{}")
            val frame2 = ParsedSseFrame(id = 2L, event = "tags", data = "{}")

            // Models the connection coroutine: applies frame1 (delivered), then frame2 — which
            // suspends on emit because the sole collector is still stuck inside frame1's callback.
            val connectionJob =
                scope.launch {
                    client.apply(SseEvent.Frame(frame1))
                    client.apply(SseEvent.Frame(frame2))
                }
            scope.testScheduler.runCurrent()

            client.currentLastEventId() shouldBe 1L

            // The engine cancelling the connection coroutine while frame2's emit is still
            // suspended — the exact scenario the fix protects: frame2 was never delivered.
            connectionJob.cancel()
            scope.testScheduler.runCurrent()

            client.currentLastEventId() shouldBe 1L

            collectorJob.cancel()
        }
    })
