package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.client.domain.model.ScanProgressState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
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
                progress?.current shouldBe 40
                progress?.total shouldBe 100
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
    })
