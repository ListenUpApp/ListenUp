package com.calypsan.listenup.server.logging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 2026-09-12 and 2026-09-25: Rupert's log driver stopped draining the container's stdout, the pipe
 * filled, and the next log line blocked the whole server — playback, sync and `/healthz` with it. A
 * log line is worth less than the request that wrote it, so the server never waits on its output.
 */
class NonBlockingLogSinkTest :
    FunSpec({

        test("a writer that cannot keep up costs log lines, never the caller") {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val writerHoldsALine = CompletableDeferred<Unit>()
            val sink =
                NonBlockingLogSink<String>(capacity = 2, scope = scope) {
                    writerHoldsALine.complete(Unit)
                    awaitCancellation()
                }

            // The writer takes the first line and never returns, as a stalled stdout write does.
            sink.offer("first")
            writerHoldsALine.await()
            repeat(100) { sink.offer("line $it") }

            sink.droppedSoFar() shouldBe 100L - 2
            scope.cancel()
        }

        test("the writer is told how many lines were lost once it catches up") {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val written = mutableListOf<String>()
            val writerHoldsALine = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val sink =
                NonBlockingLogSink<String>(
                    capacity = 2,
                    scope = scope,
                    droppedNotice = { count -> "$count dropped" },
                ) { line ->
                    writerHoldsALine.complete(Unit)
                    gate.await()
                    written += line
                }

            sink.offer("line 0")
            writerHoldsALine.await()
            (1..5).forEach { sink.offer("line $it") }
            gate.complete(Unit)
            sink.closeAndDrain(timeout = 5.seconds) shouldBe true

            written shouldContainExactly listOf("line 0", "line 1", "line 2", "3 dropped")
            scope.cancel()
        }

        test("shutdown writes everything still queued") {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val written = mutableListOf<String>()
            val sink = NonBlockingLogSink<String>(capacity = 64, scope = scope) { written += it }

            repeat(10) { sink.offer("line $it") }

            sink.closeAndDrain(timeout = 5.seconds) shouldBe true
            written shouldContainExactly List(10) { "line $it" }
            scope.cancel()
        }

        test("a wedged output cannot hold shutdown hostage") {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sink = NonBlockingLogSink<String>(capacity = 8, scope = scope) { awaitCancellation() }

            sink.offer("the line the log driver never takes")

            sink.closeAndDrain(timeout = 200.milliseconds) shouldBe false
            scope.cancel()
        }

        test("a line offered after shutdown is dropped, not an error") {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val sink = NonBlockingLogSink<String>(capacity = 8, scope = scope) { }

            sink.closeAndDrain(timeout = 5.seconds)
            sink.offer("late")

            sink.droppedSoFar() shouldBe 1L
            scope.cancel()
        }
    })
