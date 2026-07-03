package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.scanner.ScanPhase
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.event.ScanBookRef
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.EngineSnapshot
import com.calypsan.listenup.client.domain.model.ScanProgressState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for [applyScanEvent], the reducer that drives the scan-progress UI state and the
 * post-scan reconcile in [SyncRepositoryImpl].
 *
 * Two invariants under test:
 *  - `ScanEvent.Completed` invokes `reconcile` (the catch-up that pulls books the lossy live tail
 *    dropped during the scan burst) — without it, a just-scanned library shows no books until relaunch.
 *  - The initial-population gate is **server-authoritative**: Started/Progress arm `isServerScanning`
 *    only while the library has never finished its initial scan ([initialScanComplete], read from
 *    Room's server-stamped flag), and Completed clears it iff THIS run armed it ([isGateArmed]). A
 *    rescan of an already-scanned library never re-arms the "Building your library" screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncRepositoryScanProgressTest :
    FunSpec({

        fun progressEvent(
            booksAnalyzed: Int,
            filesWalked: Int,
            recentBooks: List<ScanBookRef> = emptyList(),
        ) = ScanEvent.Progress(
            correlationId = "c1",
            libraryId = LibraryId("lib-1"),
            phase = ScanPhase.ANALYZING,
            filesWalked = filesWalked,
            booksAnalyzed = booksAnalyzed,
            errors = 0,
            recentBooks = recentBooks,
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

                applyScanEvent(
                    event = completedEvent(),
                    initialScanComplete = { false },
                    isGateArmed = { true }, // this run armed the gate
                    setScanning = { scanning = it },
                    setProgress = { progress = it },
                    reconcile = { reconciled = true },
                )

                reconciled shouldBe true
                scanning shouldBe false
                progress shouldBe null
            }
        }

        // The residual onboarding bug: `ScanEvent.Completed` means the SERVER persisted the books,
        // not that the CLIENT's Room reflects them — the catch-up reconcile still has to run. Clearing
        // `scanning` (the populating gate) on Completed mounted the shell while books were still being
        // imported. The honest fix keeps the gate up until the awaited reconcile finishes, THEN clears.
        test("Completed keeps the populating gate up until the reconcile completes, then clears") {
            runTest {
                var scanning = true
                val reconcileGate = CompletableDeferred<Unit>()
                var scanningSeenDuringReconcile: Boolean? = null

                val job =
                    launch {
                        applyScanEvent(
                            event = completedEvent(),
                            initialScanComplete = { false },
                            isGateArmed = { true },
                            setScanning = { scanning = it },
                            setProgress = { },
                            reconcile = {
                                scanningSeenDuringReconcile = scanning
                                reconcileGate.await()
                            },
                        )
                    }

                // Let the reducer reach the (suspended) reconcile.
                runCurrent()
                scanningSeenDuringReconcile shouldBe true // gate still up while books import
                scanning shouldBe true

                // Import finishes → gate clears.
                reconcileGate.complete(Unit)
                job.join()
                scanning shouldBe false
            }
        }

        test("Progress maps to ScanProgressState and marks scanning, without reconciling") {
            runTest {
                var scanning: Boolean? = null
                var progress: ScanProgressState? = null
                var reconciled = false

                applyScanEvent(
                    event = progressEvent(booksAnalyzed = 40, filesWalked = 100),
                    initialScanComplete = { false },
                    isGateArmed = { false },
                    setScanning = { scanning = it },
                    setProgress = { progress = it },
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
        // The server stamps initial_scan_completed_at at the initial Completed; once that syncs into
        // Room (modelled here by flipping `serverStamped`), later scans read it and never re-arm.
        test("an incremental scan after the initial scan completes does not re-block the shell") {
            runTest {
                var scanning = false
                var serverStamped = false
                val apply: suspend (ScanEvent) -> Unit = { event ->
                    applyScanEvent(
                        event = event,
                        initialScanComplete = { serverStamped },
                        isGateArmed = { scanning },
                        setScanning = { scanning = it },
                        setProgress = { },
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

                // Server stamped the completion and it synced into Room.
                serverStamped = true

                // Watcher-driven incremental scan (fresh correlationId) — must stay out of the shell.
                apply(
                    ScanEvent.Started(correlationId = "incremental", libraryId = LibraryId("lib-1"), rootPath = "/library"),
                )
                scanning shouldBe false
                apply(progressEvent(booksAnalyzed = 1, filesWalked = 1))
                scanning shouldBe false
            }
        }

        // The "Building your library" carousel bug: the server streams a small ROLLING WINDOW of
        // recentBooks (capped per event), so rendering only the latest window showed ~8 books that
        // changed in place and looped. The client must ACCUMULATE across Progress events — dedupe by
        // (title, author), append new ones at the end — so the strip grows and never resets.
        test("Progress accumulates recentBooks across events — deduped, appended at the end") {
            runTest {
                var progress: ScanProgressState? = null
                val apply: suspend (List<ScanBookRef>) -> Unit = { window ->
                    applyScanEvent(
                        event = progressEvent(booksAnalyzed = 1, filesWalked = 1, recentBooks = window),
                        initialScanComplete = { false },
                        isGateArmed = { false },
                        setScanning = {},
                        setProgress = { progress = it },
                        reconcile = {},
                        getProgress = { progress },
                    )
                }

                apply(listOf(ScanBookRef("Dune", "Herbert"), ScanBookRef("Hyperion", "Simmons")))
                apply(listOf(ScanBookRef("Hyperion", "Simmons"), ScanBookRef("Neuromancer", "Gibson")))

                // The overlapping "Hyperion" is not duplicated; the new "Neuromancer" appends at the end.
                progress!!.recentBooks shouldBe
                    listOf(
                        ScanBookRef("Dune", "Herbert"),
                        ScanBookRef("Hyperion", "Simmons"),
                        ScanBookRef("Neuromancer", "Gibson"),
                    )
            }
        }

        // The marquee shows the front (oldest) tiles and deliberately falls behind; dropping
        // from the front to honour a cap yanked visible tiles out, causing the blink/swap. Accumulation
        // is now pure append-only — every matched book is retained, in order.
        test("recentBooks accumulation is append-only — nothing is dropped from the front") {
            runTest {
                var progress: ScanProgressState? = null
                val apply: suspend (ScanBookRef) -> Unit = { book ->
                    applyScanEvent(
                        event = progressEvent(booksAnalyzed = 1, filesWalked = 1, recentBooks = listOf(book)),
                        initialScanComplete = { false },
                        isGateArmed = { false },
                        setScanning = {},
                        setProgress = { progress = it },
                        reconcile = {},
                        getProgress = { progress },
                    )
                }

                val total = 250
                repeat(total) { i -> apply(ScanBookRef("Book $i", "Author")) }

                val recent = progress!!.recentBooks
                recent.size shouldBe total
                recent.first() shouldBe ScanBookRef("Book 0", "Author")
                recent.last() shouldBe ScanBookRef("Book ${total - 1}", "Author")
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
                            booksTotal = 1600,
                            authorsMatched = 9,
                            totalDurationMs = 7_200_000L,
                            currentFile = "A/B.m4b",
                            recentBooks = listOf(ScanBookRef("Dune", "Frank Herbert")),
                        ),
                    initialScanComplete = { false },
                    isGateArmed = { false },
                    setScanning = {},
                    setProgress = { progress = it },
                    reconcile = {},
                    nowMs = { 5_000L },
                    getStartedAt = { 1_000L },
                    setStartedAt = {},
                )
                progress!!.filesTotal shouldBe 1647
                progress!!.booksTotal shouldBe 1600
                progress!!.books shouldBe 28
                progress!!.authors shouldBe 9
                progress!!.hours shouldBe 2
                progress!!.currentFile shouldBe "A/B.m4b"
                progress!!.recentBooks shouldBe listOf(ScanBookRef("Dune", "Frank Herbert"))
                progress!!.startedAtMs shouldBe 1_000L
            }
        }

        // The persist phase: after ANALYZING hits 100%, the server emits PERSISTING progress so the bar
        // advances again under "Saving library" instead of freezing. Those events only carry book counts
        // — the rich stats (authors, hours, recent-books carousel) aren't re-sent, so the reducer must
        // PRESERVE the prior state and update only phase + save counts, or the stats panel blanks.
        test("Progress for the PERSISTING phase preserves analyze stats and only advances the save counts") {
            runTest {
                var progress: ScanProgressState? = null
                val apply: suspend (ScanEvent.Progress) -> Unit = { event ->
                    applyScanEvent(
                        event = event,
                        initialScanComplete = { false },
                        isGateArmed = { false },
                        setScanning = {},
                        setProgress = { progress = it },
                        reconcile = {},
                        getProgress = { progress },
                        nowMs = { 5_000L },
                        getStartedAt = { 1_000L },
                        setStartedAt = {},
                    )
                }

                // ANALYZING establishes the rich stats.
                apply(
                    ScanEvent.Progress(
                        "c1",
                        LibraryId("lib-1"),
                        ScanPhase.ANALYZING,
                        filesWalked = 100,
                        booksAnalyzed = 1000,
                        errors = 0,
                        totalFiles = 1647,
                        booksTotal = 1000,
                        authorsMatched = 42,
                        totalDurationMs = 7_200_000L,
                        recentBooks = listOf(ScanBookRef("Dune", "Herbert")),
                    ),
                )

                // PERSISTING carries only the save counts (as the server sends it).
                apply(
                    ScanEvent.Progress(
                        "c1",
                        LibraryId("lib-1"),
                        ScanPhase.PERSISTING,
                        filesWalked = 0,
                        booksAnalyzed = 250,
                        errors = 0,
                        booksTotal = 1000,
                    ),
                )

                // Phase + save progress advance…
                progress!!.phase shouldBe "persisting"
                progress!!.books shouldBe 250
                progress!!.booksTotal shouldBe 1000
                // …but the analyze stats are preserved, not blanked.
                progress!!.authors shouldBe 42
                progress!!.hours shouldBe 2
                progress!!.recentBooks shouldBe listOf(ScanBookRef("Dune", "Herbert"))
            }
        }

        test("Started stamps the start time") {
            runTest {
                var started = 0L
                applyScanEvent(
                    event = ScanEvent.Started("c1", LibraryId("l1"), "/root"),
                    initialScanComplete = { false },
                    isGateArmed = { false },
                    setScanning = {},
                    setProgress = {},
                    reconcile = {},
                    nowMs = { 1_234L },
                    getStartedAt = { 0L },
                    setStartedAt = { started = it },
                )
                started shouldBe 1_234L
            }
        }

        // Once the server has stamped the library's initial-scan completion (synced into Room →
        // initialScanComplete true), a fresh scan's Started/Progress must NOT arm the gate — this is
        // the Rescan-on-populated-library bug that used to flash "Building your library".
        test("Started and Progress do not arm the gate once the server has recorded initial completion") {
            runTest {
                var scanning = false
                val apply: suspend (ScanEvent) -> Unit = { event ->
                    applyScanEvent(
                        event = event,
                        initialScanComplete = { true }, // server already stamped it
                        isGateArmed = { scanning },
                        setScanning = { scanning = it },
                        setProgress = { },
                        reconcile = { },
                    )
                }

                apply(ScanEvent.Started("rescan", LibraryId("lib-1"), "/library"))
                scanning shouldBe false
                apply(progressEvent(booksAnalyzed = 3, filesWalked = 9))
                scanning shouldBe false
            }
        }

        // The strand: the terminal `ScanEvent.Completed` travels over a replay=0 bus, so a
        // progress stream that drops or re-establishes mid-scan can miss it — holding the
        // populating gate (`isServerScanning`) up forever. [recoverFromScanStreamEnd] is the
        // never-stranded recovery run whenever the stream terminates while the gate is still armed:
        // confirm the scan really finished (the server's authoritative lastScanResult), then
        // reconcile + clear. Confirm-then-clear — never strands, never clears early.
        test("recovery clears the gate when the missed-Completed scan is confirmed finished") {
            runTest {
                var scanning = true
                var progress: ScanProgressState? = ScanProgressState("ANALYZING", 72, 1647, 0, 0, 0)
                var reconciled = false

                recoverFromScanStreamEnd(
                    isScanning = { scanning },
                    confirmScanFinished = { true },
                    reconcile = { reconciled = true },
                    setScanning = { scanning = it },
                    setProgress = { progress = it },
                )

                reconciled shouldBe true // pulled the books the missed Completed would have
                scanning shouldBe false // gate cleared — user escapes the populating screen
                progress shouldBe null
            }
        }

        test("recovery keeps the gate up when completion is not confirmed (scan still running / server unreachable)") {
            runTest {
                var scanning = true
                var reconciled = false

                recoverFromScanStreamEnd(
                    isScanning = { scanning },
                    confirmScanFinished = { false },
                    reconcile = { reconciled = true },
                    setScanning = { scanning = it },
                    setProgress = { },
                )

                scanning shouldBe true // genuinely mid-scan — keep the gate, re-subscribe
                reconciled shouldBe false // confirm-first: no wasted catch-up while still scanning
            }
        }

        test("recovery is a no-op when the gate is not armed") {
            runTest {
                var reconciled = false
                var scanningWrites = 0

                recoverFromScanStreamEnd(
                    isScanning = { false },
                    confirmScanFinished = { true },
                    reconcile = { reconciled = true },
                    setScanning = { scanningWrites++ },
                    setProgress = { },
                )

                reconciled shouldBe false
                scanningWrites shouldBe 0
            }
        }

        // ── Offline busy-loop gate ──────────────────────────────────────────────────────────────────
        //
        // `observeScanProgressResiliently` re-subscribed the scanner RPC stream every 2s unconditionally.
        // While the server is offline each subscribe throws "RpcClient was cancelled" immediately and logs
        // a stacktrace — a 2s busy-loop of log spam. The gate suspends the loop until the engine is
        // actually Connected, so an offline client is idle and silent instead of hammering a dead stream.

        test("awaitServerConnected suspends while disconnected and resumes when connected") {
            runTest {
                val snapshots = MutableStateFlow(EngineSnapshot(connection = ConnectionState.Disconnected(reason = null)))
                var resumed = false

                val job =
                    launch {
                        awaitServerConnected(snapshots)
                        resumed = true
                    }

                runCurrent()
                resumed shouldBe false // offline → suspended, no re-subscribe, no spam

                snapshots.value = EngineSnapshot(connection = ConnectionState.Connecting)
                runCurrent()
                resumed shouldBe false // a connect attempt in flight is not yet a usable stream

                snapshots.value = EngineSnapshot(connection = ConnectionState.Connected(lastEventId = null))
                runCurrent()
                resumed shouldBe true // connected → loop proceeds to subscribe
                job.join()
            }
        }

        test("awaitServerConnected returns immediately when already connected") {
            runTest {
                val snapshots = MutableStateFlow(EngineSnapshot(connection = ConnectionState.Connected(lastEventId = 7L)))
                var resumed = false

                launch {
                    awaitServerConnected(snapshots)
                    resumed = true
                }
                runCurrent()

                resumed shouldBe true
            }
        }

        // ── Idempotent-scan gate ──────────────────────────────────────────────────────────────────
        //
        // When a scan_completed reports zero row changes (added + modified + removed + moved all zero),
        // the client must skip the expensive catch-up reconcile (19 HTTP round-trips + SSE teardown)
        // and the FTS rebuild (~3.3 s on a 1 150-book library). The initial-population gate must still
        // clear correctly so the shell isn't stranded.

        test("Completed with no row changes skips reconcile for an incremental scan") {
            runTest {
                var reconciled = false

                applyScanEvent(
                    event =
                        ScanEvent.Completed(
                            correlationId = "incr",
                            libraryId = LibraryId("lib-1"),
                            result =
                                ScanResultSummary(
                                    correlationId = "incr",
                                    totalBooks = 1150,
                                    added = 0,
                                    modified = 0,
                                    removed = 0,
                                    moved = 0,
                                    errors = 0,
                                    durationMs = 800,
                                    filesWalked = 2300,
                                ),
                        ),
                    initialScanComplete = { true },
                    isGateArmed = { false }, // incremental — this run never armed the gate
                    setScanning = { },
                    setProgress = { },
                    reconcile = { reconciled = true },
                )

                reconciled shouldBe false // no row changes → no catch-up, no FTS rebuild
            }
        }

        test("Completed with actual changes still reconciles for an incremental scan") {
            runTest {
                var reconciled = false

                applyScanEvent(
                    event =
                        ScanEvent.Completed(
                            correlationId = "incr",
                            libraryId = LibraryId("lib-1"),
                            result =
                                ScanResultSummary(
                                    correlationId = "incr",
                                    totalBooks = 1151,
                                    added = 1,
                                    modified = 0,
                                    removed = 0,
                                    moved = 0,
                                    errors = 0,
                                    durationMs = 900,
                                    filesWalked = 2302,
                                ),
                        ),
                    initialScanComplete = { true },
                    isGateArmed = { false }, // incremental
                    setScanning = { },
                    setProgress = { },
                    reconcile = { reconciled = true },
                )

                reconciled shouldBe true // new book found → must catch-up and rebuild FTS
            }
        }

        // Ensure the initial-population gate is still correctly cleared even when reconcile is
        // skipped. A zero-change first scan (edge case: user triggers a manual scan immediately
        // after onboarding with nothing new on disk) must not strand the shell.
        test("Completed with no row changes on the initial scan clears the populating gate without reconciling") {
            runTest {
                var reconciled = false
                var scanning = true

                applyScanEvent(
                    event =
                        ScanEvent.Completed(
                            correlationId = "init",
                            libraryId = LibraryId("lib-1"),
                            result =
                                ScanResultSummary(
                                    correlationId = "init",
                                    totalBooks = 0,
                                    added = 0,
                                    modified = 0,
                                    removed = 0,
                                    moved = 0,
                                    errors = 0,
                                    durationMs = 200,
                                    filesWalked = 0,
                                ),
                        ),
                    initialScanComplete = { false },
                    isGateArmed = { true }, // this run armed the gate
                    setScanning = { scanning = it },
                    setProgress = { },
                    reconcile = { reconciled = true },
                )

                reconciled shouldBe false // nothing changed — no expensive work
                scanning shouldBe false // gate cleared so the shell renders
            }
        }

        // Verify that each individual change-type (modified, removed, moved) independently
        // triggers reconcile — it is not just `added` that counts.
        test("scanResultHasChanges returns true when only modified rows are present") {
            scanResultHasChanges(
                ScanResultSummary(
                    "x",
                    totalBooks = 5,
                    added = 0,
                    modified = 2,
                    removed = 0,
                    moved = 0,
                    errors = 0,
                    durationMs = 10,
                    filesWalked = 10,
                ),
            ) shouldBe true
        }

        test("scanResultHasChanges returns true when only removed rows are present") {
            scanResultHasChanges(
                ScanResultSummary(
                    "x",
                    totalBooks = 3,
                    added = 0,
                    modified = 0,
                    removed = 1,
                    moved = 0,
                    errors = 0,
                    durationMs = 10,
                    filesWalked = 10,
                ),
            ) shouldBe true
        }

        test("scanResultHasChanges returns true when only moved rows are present") {
            scanResultHasChanges(
                ScanResultSummary(
                    "x",
                    totalBooks = 5,
                    added = 0,
                    modified = 0,
                    removed = 0,
                    moved = 3,
                    errors = 0,
                    durationMs = 10,
                    filesWalked = 10,
                ),
            ) shouldBe true
        }

        test("scanResultHasChanges returns false when all counters are zero") {
            scanResultHasChanges(
                ScanResultSummary(
                    "x",
                    totalBooks = 1150,
                    added = 0,
                    modified = 0,
                    removed = 0,
                    moved = 0,
                    errors = 0,
                    durationMs = 800,
                    filesWalked = 2300,
                ),
            ) shouldBe false
        }
    })
