package com.calypsan.listenup.client.download

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Scheduling and finishing cannot race: a file queued while a run finishes is always either picked up by that run or
 * given a new one. And books take turns, first come first served.
 */
class BookTransferRegistryTest :
    FunSpec({
        test("a schedule while a run is registered is left to the run, which picks the new file up before it exits") {
            runTest {
                val registry = BookTransferRegistry()
                val queue = ArrayDeque<String>()
                val token = registry.register("b1")

                // The tap: a new file is queued, then scheduling is asked for while the run is still registered.
                queue.addLast("f2")
                registry.scheduleUnlessRunning("b1") { "scheduled" } shouldBe null

                // The run drains the queue under the lock, and only exits when nothing is left.
                registry.nextOrFinish(token) { queue.removeFirstOrNull() } shouldBe "f2"
                registry.nextOrFinish(token) { queue.removeFirstOrNull() } shouldBe null
                registry.isRunning("b1") shouldBe false
            }
        }

        test("once the run has finished, the next schedule starts a new one") {
            runTest {
                val registry = BookTransferRegistry()
                val token = registry.register("b1")
                registry.nextOrFinish<String>(token) { null } shouldBe null

                registry.scheduleUnlessRunning("b1") { "scheduled" } shouldBe "scheduled"
            }
        }

        test("every file queued concurrently with a run's exit is either drained or scheduled, never orphaned") {
            runTest {
                repeat(200) { round ->
                    val registry = BookTransferRegistry()
                    val queue = ArrayDeque<Int>()
                    val token = registry.register("b1")
                    val drained = mutableListOf<Int>()
                    var scheduled = false

                    val run =
                        launch {
                            while (true) drained += registry.nextOrFinish(token) { queue.removeFirstOrNull() } ?: break
                        }
                    val tap =
                        launch {
                            queue.addLast(round)
                            scheduled = registry.scheduleUnlessRunning("b1") { true } == true
                        }
                    run.join()
                    tap.join()

                    (round in drained || scheduled) shouldBe true
                }
            }
        }

        test("one book transfers at a time, in the order the runs asked for a turn") {
            runTest {
                val registry = BookTransferRegistry()
                val order = mutableListOf<String>()
                val releaseFirst = CompletableDeferred<Unit>()

                val first =
                    async {
                        registry.withTurn {
                            order += "A start"
                            releaseFirst.await()
                            order += "A end"
                        }
                    }
                advanceUntilIdle()
                val second = async { registry.withTurn { order += "B" } }
                val third = async { registry.withTurn { order += "C" } }
                advanceUntilIdle()
                order shouldBe listOf("A start")

                releaseFirst.complete(Unit)
                first.await()
                second.await()
                third.await()
                order shouldBe listOf("A start", "A end", "B", "C")
            }
        }

        test("a stop the app announces is read by the run it was announced for, not by a later one") {
            runTest {
                val registry = BookTransferRegistry()
                val old = registry.register("b1")
                registry.expectStop("b1", DownloadStopReason.PREFERENCE_CHANGED)
                val replacement = registry.register("b1")

                registry.expectedStopOf(old) shouldBe DownloadStopReason.PREFERENCE_CHANGED
                registry.expectedStopOf(replacement) shouldBe null
            }
        }
    })
