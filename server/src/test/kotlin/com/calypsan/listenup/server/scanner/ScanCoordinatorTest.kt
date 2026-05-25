@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.LibraryId
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ScanCoordinatorTest :
    FunSpec({

        test("concurrent scanFull() — the second caller gets AlreadyRunning") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val coordinator =
                    ScanCoordinator(
                        libraryId = LibraryId("test-lib"),
                        runFullScan = {
                            gate.await()
                            emptyResult()
                        },
                        runIncremental = { /* unused */ },
                        scope = backgroundScope,
                    )

                val first = async { coordinator.scanFull() }
                runCurrent() // let `first` acquire the mutex and suspend on the gate

                val second = coordinator.scanFull()
                second.shouldBeInstanceOf<AppResult.Failure>()
                second.error.shouldBeInstanceOf<ScanError.AlreadyRunning>()

                gate.complete(Unit)
                first.await().shouldBeInstanceOf<AppResult.Success<ScanResult>>()
            }
        }

        test("incremental triggered during a full scan runs after the scan finishes") {
            runTest {
                val incrementalCalls = mutableListOf<Path>()
                val gate = CompletableDeferred<Unit>()
                val coordinator =
                    ScanCoordinator(
                        libraryId = LibraryId("test-lib"),
                        runFullScan = {
                            gate.await()
                            emptyResult()
                        },
                        runIncremental = { p -> incrementalCalls.add(p) },
                        scope = backgroundScope,
                    )

                val full = async { coordinator.scanFull() }
                runCurrent() // full scan now holds the mutex

                coordinator.reanalyze(Path.of("/library/Author/Title"))
                runCurrent()
                withClue("worker must not run while full scan holds the mutex") {
                    incrementalCalls.shouldContainExactly(emptyList())
                }

                gate.complete(Unit)
                full.await()
                runCurrent()

                incrementalCalls shouldContainExactly listOf(Path.of("/library/Author/Title"))
            }
        }

        test("100 reanalyze() triggers for the same path coalesce to a single invocation") {
            runTest {
                val invocations = AtomicInteger(0)
                val coordinator =
                    ScanCoordinator(
                        libraryId = LibraryId("test-lib"),
                        runFullScan = { emptyResult() },
                        runIncremental = { invocations.incrementAndGet() },
                        scope = backgroundScope,
                    )

                val path = Path.of("/library/Author/Hot Folder")
                repeat(100) { coordinator.reanalyze(path) }
                runCurrent() // worker drains the queue

                invocations.get() shouldBe 1
            }
        }

        test("a path re-triggered after processing finishes runs again") {
            runTest {
                val invocations = AtomicInteger(0)
                val coordinator =
                    ScanCoordinator(
                        libraryId = LibraryId("test-lib"),
                        runFullScan = { emptyResult() },
                        runIncremental = { invocations.incrementAndGet() },
                        scope = backgroundScope,
                    )

                val path = Path.of("/library/Author/Title")
                coordinator.reanalyze(path)
                runCurrent()
                invocations.get() shouldBe 1

                // The first run completed, the path is no longer pending — the
                // second trigger should re-enqueue it (the file may have changed
                // again since the worker started).
                coordinator.reanalyze(path)
                runCurrent()
                invocations.get() shouldBe 2
            }
        }

        test("distinct paths fired concurrently are NOT coalesced") {
            runTest {
                val seen = mutableListOf<Path>()
                val coordinator =
                    ScanCoordinator(
                        libraryId = LibraryId("test-lib"),
                        runFullScan = { emptyResult() },
                        runIncremental = { p -> seen.add(p) },
                        scope = backgroundScope,
                    )

                val a = Path.of("/library/A")
                val b = Path.of("/library/B")
                val c = Path.of("/library/C")
                coordinator.reanalyze(a)
                coordinator.reanalyze(b)
                coordinator.reanalyze(c)
                runCurrent()

                seen shouldContainExactly listOf(a, b, c)
            }
        }

        test("an exception inside runIncremental is logged and the worker continues") {
            runTest {
                val seen = mutableListOf<Path>()
                val coordinator =
                    ScanCoordinator(
                        libraryId = LibraryId("test-lib"),
                        runFullScan = { emptyResult() },
                        runIncremental = { p ->
                            if (p == Path.of("/boom")) error("simulated failure")
                            seen.add(p)
                        },
                        scope = backgroundScope,
                    )

                coordinator.reanalyze(Path.of("/before"))
                coordinator.reanalyze(Path.of("/boom"))
                coordinator.reanalyze(Path.of("/after"))
                runCurrent()

                seen shouldContainExactly listOf(Path.of("/before"), Path.of("/after"))
            }
        }

        test("cancelling the calling job propagates into an in-flight full scan") {
            runTest {
                val cancellationCaught = AtomicReference<Throwable?>(null)
                val started = CompletableDeferred<Unit>()
                val coordinator =
                    ScanCoordinator(
                        libraryId = LibraryId("test-lib"),
                        runFullScan = {
                            started.complete(Unit)
                            try {
                                awaitCancellation()
                            } catch (e: CancellationException) {
                                cancellationCaught.set(e)
                                throw e
                            }
                        },
                        runIncremental = { /* unused */ },
                        scope = backgroundScope,
                    )

                val job =
                    launch {
                        try {
                            coordinator.scanFull()
                        } catch (_: CancellationException) {
                            // Expected — cancellation propagates out of scanFull.
                        }
                    }
                started.await()
                job.cancel()
                job.join()

                cancellationCaught.get().shouldBeInstanceOf<CancellationException>()
            }
        }

        test("a busy reanalyze worker doesn't block scanFull from acquiring the mutex eventually") {
            runTest {
                val incrementalGate = CompletableDeferred<Unit>()
                val coordinator =
                    ScanCoordinator(
                        libraryId = LibraryId("test-lib"),
                        runFullScan = { emptyResult() },
                        runIncremental = { incrementalGate.await() },
                        scope = backgroundScope,
                    )

                coordinator.reanalyze(Path.of("/slow"))
                runCurrent() // worker takes mutex, suspends on gate

                val full = async { coordinator.scanFull() }
                runCurrent()

                // While the incremental holds the mutex, scanFull tryLock fails.
                full.isCompleted shouldBe true
                full.await().shouldBeInstanceOf<AppResult.Failure>()

                // Release the gate so the worker can finish.
                incrementalGate.complete(Unit)

                // After the worker releases the mutex, a fresh scanFull succeeds.
                runCurrent()
                delay(1)
                val after = coordinator.scanFull()
                after.shouldBeInstanceOf<AppResult.Success<ScanResult>>()
            }
        }
    })

private fun emptyResult(): ScanResult =
    ScanResult(
        correlationId = "test",
        rootPath = "/library",
        books = emptyList(),
        changes = emptyList(),
        errors = emptyList(),
        durationMs = 0,
        filesWalked = 0,
        filesSkipped = 0,
    )
