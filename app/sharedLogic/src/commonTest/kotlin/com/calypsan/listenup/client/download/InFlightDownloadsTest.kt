package com.calypsan.listenup.client.download

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The ownership and concurrency-cap rules the iOS download service depends on: one transfer per
 * file, a cap on how many run at once, and a cancel that reaches files still waiting for a slot.
 */
class InFlightDownloadsTest :
    FunSpec({

        fun InFlightDownloads.launchIds(
            scope: TestScope,
            permits: Semaphore,
            bookId: String,
            ids: List<String>,
            action: suspend (String) -> Unit,
        ): Int = launchNew(scope, permits, bookId, ids, idOf = { it }, action = action)

        test("at most permits actions run at the same time") {
            runTest {
                val registry = InFlightDownloads()
                val gate = CompletableDeferred<Unit>()
                var running = 0
                var peak = 0

                registry.launchIds(this, Semaphore(2), "book", (1..10).map { "f$it" }) {
                    running++
                    if (running > peak) peak = running
                    gate.await()
                    running--
                }

                runCurrent()
                peak shouldBe 2

                gate.complete(Unit)
                advanceUntilIdle()
                peak shouldBe 2
                running shouldBe 0
            }
        }

        test("a file already owned is not launched a second time") {
            runTest {
                val registry = InFlightDownloads()
                val permits = Semaphore(5)
                val gate = CompletableDeferred<Unit>()
                val runs = mutableListOf<String>()
                val action: suspend (String) -> Unit = { id ->
                    runs += id
                    gate.await()
                }

                registry.launchIds(this, permits, "book", listOf("a", "b"), action) shouldBe 2
                runCurrent()
                registry.launchIds(this, permits, "book", listOf("a", "b", "c"), action) shouldBe 1
                runCurrent()

                runs.sorted() shouldBe listOf("a", "b", "c")
                gate.complete(Unit)
                advanceUntilIdle()
            }
        }

        test("a parked file is owned too") {
            runTest {
                val registry = InFlightDownloads()
                val permits = Semaphore(1)
                val gate = CompletableDeferred<Unit>()
                val action: suspend (String) -> Unit = { gate.await() }

                registry.launchIds(this, permits, "book", listOf("a", "b"), action)
                runCurrent()

                registry.owns("b") shouldBe true
                registry.launchIds(this, permits, "book", listOf("b"), action) shouldBe 0

                gate.complete(Unit)
                advanceUntilIdle()
            }
        }

        test("a finished file can be launched again") {
            runTest {
                val registry = InFlightDownloads()
                val permits = Semaphore(1)

                registry.launchIds(this, permits, "book", listOf("a")) { }
                runCurrent()

                registry.owns("a") shouldBe false
                registry.launchIds(this, permits, "book", listOf("a")) { } shouldBe 1
                advanceUntilIdle()
            }
        }

        test("cancelBook cancels running and parked files of that book only") {
            runTest {
                val registry = InFlightDownloads()
                val permits = Semaphore(1)
                val gate = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                val cancelled = mutableListOf<String>()
                val action: suspend (String) -> Unit = { id ->
                    started += id
                    try {
                        gate.await()
                    } finally {
                        if (!gate.isCompleted) cancelled += id
                    }
                }

                registry.launchIds(this, permits, "x", listOf("a", "b"), action)
                registry.launchIds(this, permits, "y", listOf("c"), action)
                runCurrent()
                started shouldBe listOf("a")

                registry.cancelBook("x") shouldBe 2
                runCurrent()
                cancelled shouldBe listOf("a")

                gate.complete(Unit)
                advanceUntilIdle()
                started shouldBe listOf("a", "c")
            }
        }

        test("cancelAllAndJoin leaves nothing to start") {
            runTest {
                val registry = InFlightDownloads()
                val permits = Semaphore(1)
                val gate = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                var runningObservedCancellation = false
                val action: suspend (String) -> Unit = { id ->
                    started += id
                    try {
                        gate.await()
                    } finally {
                        if (id == "a") runningObservedCancellation = true
                    }
                }

                registry.launchIds(this, permits, "book", listOf("a", "b", "c"), action)
                runCurrent()
                started shouldBe listOf("a")

                registry.cancelAllAndJoin()

                runningObservedCancellation shouldBe true
                listOf("a", "b", "c").map { registry.owns(it) } shouldBe listOf(false, false, false)
                gate.complete(Unit)
                advanceUntilIdle()
                started shouldBe listOf("a")
            }
        }
    })
