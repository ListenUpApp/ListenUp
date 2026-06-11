package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.event.ScanBookRef
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.client.domain.model.ScanProgressState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [applyScanEvent], the reducer that drives the scan-progress UI state and the
 * post-scan reconcile in [SyncRepositoryImpl].
 *
 * The key regression: `ScanEvent.Completed` must invoke `reconcile` (the catch-up that pulls
 * books the lossy live tail dropped during the scan burst) — without it, a just-scanned library
 * shows no books until the app is relaunched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncRepositoryScanProgressTest :
    FunSpec({

        fun progressEvent(
            booksAnalyzed: Int,
            filesWalked: Int,
        ) = ScanEvent.Progress(
            correlationId = "c1",
            libraryId = LibraryId("lib-1"),
            phase = ScanPhase.ANALYZING,
            filesWalked = filesWalked,
            booksAnalyzed = booksAnalyzed,
            errors = 0,
        )

        fun completedEvent() =
            ScanEvent.Completed(
                correlationId = "c1",
                libraryId = LibraryId("lib-1"),
                result =
                    ScanResultSummary(
                        correlationId = "c1",
                        totalBooks = 1,
                        added = 1,
                        modified = 0,
                        removed = 0,
                        moved = 0,
                        errors = 0,
                        durationMs = 10,
                        filesWalked = 1,
                    ),
            )

        test("Completed triggers a reconcile and clears scanning state") {
            runTest {
                var scanning: Boolean? = null
                var progress: ScanProgressState? = ScanProgressState("ANALYZING", 5, 10, 0, 0, 0)
                var reconciled = false
                var initialComplete = false

                applyScanEvent(
                    event = completedEvent(),
                    isInitialScanComplete = { initialComplete },
                    setScanning = { scanning = it },
                    setProgress = { progress = it },
                    markInitialScanComplete = { initialComplete = true },
                    reconcile = { reconciled = true },
                )

                reconciled shouldBe true
                scanning shouldBe false
                progress shouldBe null
                initialComplete shouldBe true
            }
        }

        // The residual onboarding bug: `ScanEvent.Completed` means the SERVER persisted the books,
        // not that the CLIENT's Room reflects them — the catch-up reconcile still has to run. Clearing
        // `scanning` (the populating gate) on Completed mounted the shell while books were still being
        // imported. The honest fix keeps the gate up until the awaited reconcile finishes, THEN clears
        // and latches.
        test("Completed keeps the populating gate up until the reconcile completes, then clears and latches") {
            runTest {
                var scanning = true
                var initialComplete = false
                val reconcileGate = CompletableDeferred<Unit>()
                var scanningSeenDuringReconcile: Boolean? = null
                var initialSeenDuringReconcile: Boolean? = null

                val job =
                    launch {
                        applyScanEvent(
                            event = completedEvent(),
                            isInitialScanComplete = { initialComplete },
                            setScanning = { scanning = it },
                            setProgress = { },
                            markInitialScanComplete = { initialComplete = true },
                            reconcile = {
                                scanningSeenDuringReconcile = scanning
                                initialSeenDuringReconcile = initialComplete
                                reconcileGate.await()
                            },
                        )
                    }

                // Let the reducer reach the (suspended) reconcile.
                runCurrent()
                scanningSeenDuringReconcile shouldBe true // gate still up while books import
                initialSeenDuringReconcile shouldBe false // not latched until import finishes
                scanning shouldBe true

                // Import finishes → gate clears, readiness latches.
                reconcileGate.complete(Unit)
                job.join()
                scanning shouldBe false
                initialComplete shouldBe true
            }
        }

        test("Progress maps to ScanProgressState and marks scanning, without reconciling") {
            runTest {
                var scanning: Boolean? = null
                var progress: ScanProgressState? = null
                var reconciled = false

                applyScanEvent(
                    event = progressEvent(booksAnalyzed = 40, filesWalked = 100),
                    isInitialScanComplete = { false },
                    setScanning = { scanning = it },
                    setProgress = { progress = it },
                    markInitialScanComplete = { },
                    reconcile = { reconciled = true },
                )

                scanning shouldBe true
                progress?.current shouldBe 100
                progress?.total shouldBe 100
                progress?.books shouldBe 40
                reconciled shouldBe false
            }
        }

        // The regression behind the latched full-screen overlay: a watcher-driven incremental scan
        // emits its own Started/Progress AFTER the initial scan completes. Those must NOT re-arm the
        // app-readiness flag, or they slam the "Scanning…" overlay back over an already-usable library.
        test("an incremental scan after the initial scan completes does not re-block the shell") {
            runTest {
                var scanning = false
                var initialComplete = false
                val apply: suspend (ScanEvent) -> Unit = { event ->
                    applyScanEvent(
                        event = event,
                        isInitialScanComplete = { initialComplete },
                        setScanning = { scanning = it },
                        setProgress = { },
                        markInitialScanComplete = { initialComplete = true },
                        reconcile = { },
                    )
                }

                // Initial population scan.
                apply(
                    ScanEvent.Started(correlationId = "full", libraryId = LibraryId("lib-1"), rootPath = "/library"),
                )
                scanning shouldBe true
                apply(progressEvent(booksAnalyzed = 10, filesWalked = 20))
                scanning shouldBe true
                apply(completedEvent())
                scanning shouldBe false
                initialComplete shouldBe true

                // Watcher-driven incremental scan (fresh correlationId) — must stay out of the shell.
                apply(
                    ScanEvent.Started(correlationId = "incremental", libraryId = LibraryId("lib-1"), rootPath = "/library"),
                )
                scanning shouldBe false
                apply(progressEvent(booksAnalyzed = 1, filesWalked = 1))
                scanning shouldBe false
            }
        }

        test("Progress maps the enriched fields") {
            runTest {
                var progress: ScanProgressState? = null
                applyScanEvent(
                    event =
                        ScanEvent.Progress(
                            "c1",
                            LibraryId("l1"),
                            ScanPhase.ANALYZING,
                            filesWalked = 100,
                            booksAnalyzed = 28,
                            errors = 0,
                            totalFiles = 1647,
                            authorsMatched = 9,
                            totalDurationMs = 7_200_000L,
                            currentFile = "A/B.m4b",
                            recentBooks = listOf(ScanBookRef("Dune", "Frank Herbert")),
                        ),
                    isInitialScanComplete = { false },
                    setScanning = {},
                    setProgress = { progress = it },
                    markInitialScanComplete = {},
                    reconcile = {},
                    nowMs = { 5_000L },
                    getStartedAt = { 1_000L },
                    setStartedAt = {},
                )
                progress!!.filesTotal shouldBe 1647
                progress!!.books shouldBe 28
                progress!!.authors shouldBe 9
                progress!!.hours shouldBe 2
                progress!!.currentFile shouldBe "A/B.m4b"
                progress!!.recentBooks shouldBe listOf(ScanBookRef("Dune", "Frank Herbert"))
                progress!!.startedAtMs shouldBe 1_000L
            }
        }

        test("Started stamps the start time") {
            runTest {
                var started = 0L
                applyScanEvent(
                    event = ScanEvent.Started("c1", LibraryId("l1"), "/root"),
                    isInitialScanComplete = { false },
                    setScanning = {},
                    setProgress = {},
                    markInitialScanComplete = {},
                    reconcile = {},
                    nowMs = { 1_234L },
                    getStartedAt = { 0L },
                    setStartedAt = { started = it },
                )
                started shouldBe 1_234L
            }
        }

        // The strand: the terminal `ScanEvent.Completed` travels over a replay=0 bus, so a
        // progress stream that drops or re-establishes mid-scan can miss it — latching the
        // populating gate (`isServerScanning`) forever. [recoverFromScanStreamEnd] is the
        // never-stranded recovery run whenever the stream terminates while the initial gate is
        // still up: confirm the scan really finished (the server's authoritative lastScanResult),
        // then reconcile + clear + latch. Confirm-then-clear — never strands, never latches early.
        test("recovery clears the gate when the missed-Completed scan is confirmed finished") {
            runTest {
                var scanning = true
                var progress: ScanProgressState? = ScanProgressState("ANALYZING", 72, 1647, 0, 0, 0)
                var initialComplete = false
                var reconciled = false

                recoverFromScanStreamEnd(
                    isInitialScanComplete = { initialComplete },
                    isScanning = { scanning },
                    confirmScanFinished = { true },
                    reconcile = { reconciled = true },
                    setScanning = { scanning = it },
                    setProgress = { progress = it },
                    markInitialScanComplete = { initialComplete = true },
                )

                reconciled shouldBe true // pulled the books the missed Completed would have
                scanning shouldBe false // gate cleared — user escapes the populating screen
                progress shouldBe null
                initialComplete shouldBe true // latched so a later incremental can't re-block
            }
        }

        test("recovery keeps the gate up when completion is not confirmed (scan still running / server unreachable)") {
            runTest {
                var scanning = true
                var initialComplete = false
                var reconciled = false

                recoverFromScanStreamEnd(
                    isInitialScanComplete = { initialComplete },
                    isScanning = { scanning },
                    confirmScanFinished = { false },
                    reconcile = { reconciled = true },
                    setScanning = { scanning = it },
                    setProgress = { },
                    markInitialScanComplete = { initialComplete = true },
                )

                scanning shouldBe true // genuinely mid-scan — keep the gate, re-subscribe
                initialComplete shouldBe false
                reconciled shouldBe false // confirm-first: no wasted catch-up while still scanning
            }
        }

        test("recovery is a no-op once the initial population has already latched") {
            runTest {
                var reconciled = false
                var scanningWrites = 0

                recoverFromScanStreamEnd(
                    isInitialScanComplete = { true },
                    isScanning = { false },
                    confirmScanFinished = { true },
                    reconcile = { reconciled = true },
                    setScanning = { scanningWrites++ },
                    setProgress = { },
                    markInitialScanComplete = { },
                )

                reconciled shouldBe false
                scanningWrites shouldBe 0
            }
        }
    })
