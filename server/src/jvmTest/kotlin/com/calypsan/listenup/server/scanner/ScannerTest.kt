@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanScope
import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.api.error.ScanError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.io.isUnder
import com.calypsan.listenup.server.io.relativeTo
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.testing.testLibrary
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.util.concurrent.atomic.AtomicLong

class ScannerTest :
    FunSpec({

        test("first full scan emits Added for every book") {
            runTest {
                audioLibrary {
                    book("Author/Title-A") { tracks(count = 2) }
                    book("Author/Title-B") { tracks(count = 1) }
                }.use { fixture ->
                    val (scanner, eventBus) = newScanner(fixture)

                    val result = scanner.runFullScan()
                    result.books.size shouldBe 2
                    result.changes.size shouldBe 2
                    result.changes.all { it is ChangeEventDto.Added } shouldBe true

                    // The Scanner emits the scanning-phase events only. ScanEvent.Completed is emitted
                    // by BookPersister AFTER persistence (see BookPersisterTest), so it is NOT here.
                    val events = eventBus.replayCache
                    events.first().shouldBeInstanceOf<ScanEvent.Started>()
                    events.none { it is ScanEvent.Completed } shouldBe true
                    events.count { it is ScanEvent.Change } shouldBe 2
                }
            }
        }

        test("a second full scan with no changes emits zero Change events") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 2) }
                }.use { fixture ->
                    val (scanner, eventBus) = newScanner(fixture)
                    scanner.runFullScan() // first scan: 1 Added
                    eventBus.resetReplayCache()

                    val result = scanner.runFullScan()
                    result.changes shouldBe emptyList()
                    eventBus.replayCache.count { it is ScanEvent.Change } shouldBe 0
                }
            }
        }

        test("lastResult is updated after a full scan") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 1) }
                }.use { fixture ->
                    val (scanner, _) = newScanner(fixture)
                    scanner.lastResult() shouldBe null

                    scanner.runFullScan()
                    val last = scanner.lastResult()
                    last.shouldNotBeNull()
                    last.books.size shouldBe 1
                }
            }
        }

        test("monotonic correlation IDs across scans") {
            runTest {
                audioLibrary { book("Author/Title") { tracks(count = 1) } }.use { fixture ->
                    val counter = AtomicLong(0)
                    val (scanner, _) =
                        newScanner(
                            fixture,
                            correlationIdFactory = { "scan-${counter.incrementAndGet()}" },
                        )
                    scanner.runFullScan().correlationId shouldBe "scan-1"
                    scanner.runFullScan().correlationId shouldBe "scan-2"
                }
            }
        }

        test("incremental analyzes only the targeted subtree and patches lastResult") {
            runTest {
                audioLibrary {
                    book("Author/Stays") { tracks(count = 1) }
                    book("Author/Updated") { tracks(count = 1) }
                }.use { fixture ->
                    val (scanner, eventBus) = newScanner(fixture)
                    scanner.runFullScan()
                    eventBus.resetReplayCache()

                    // Add a track to "Updated" only.
                    fixture.book("Author/Updated") { audio("02 - New.mp3") }

                    val targetRoot = Path(fixture.root.resolve("Author/Updated").toString())
                    scanner.runIncremental(targetRoot)

                    val last = scanner.lastResult().shouldNotBeNull()
                    last.books.size shouldBe 2
                    val updated = last.books.single { it.candidate.rootRelPath == "Author/Updated" }
                    updated.candidate.files.size shouldBe 2

                    // The Change event should be Modified for the updated book root,
                    // and the unaffected book ("Stays") should NOT appear in changes.
                    val changes = eventBus.replayCache.filterIsInstance<ScanEvent.Change>().map { it.event }
                    changes.size shouldBe 1
                    changes.single().shouldBeInstanceOf<ChangeEventDto.Modified>()
                }
            }
        }

        test("incremental against a deleted book root emits Removed") {
            runTest {
                audioLibrary {
                    book("Author/Stays") { tracks(count = 1) }
                    book("Author/Goes Away") { tracks(count = 1) }
                }.use { fixture ->
                    val (scanner, eventBus) = newScanner(fixture)
                    scanner.runFullScan()
                    eventBus.resetReplayCache()

                    // Delete the book directory entirely.
                    val targetRootNio = fixture.root.resolve("Author/Goes Away")
                    targetRootNio.toFile().deleteRecursively()
                    val targetRoot = Path(targetRootNio.toString())

                    scanner.runIncremental(targetRoot)

                    val changes = eventBus.replayCache.filterIsInstance<ScanEvent.Change>().map { it.event }
                    changes.size shouldBe 1
                    val removed = changes.single().shouldBeInstanceOf<ChangeEventDto.Removed>()
                    removed.rootRelPath shouldBe "Author/Goes Away"

                    val last = scanner.lastResult().shouldNotBeNull()
                    last.books.map { it.candidate.rootRelPath } shouldContain "Author/Stays"
                    last.books.map { it.candidate.rootRelPath }.contains("Author/Goes Away") shouldBe false
                }
            }
        }

        test("ScanResult.toSummary counts Added/Modified/Removed/Moved correctly") {
            runTest {
                audioLibrary {
                    book("Author/A") { tracks(count = 1) }
                    book("Author/B") { tracks(count = 1) }
                }.use { fixture ->
                    val (scanner, _) = newScanner(fixture)
                    val first = scanner.runFullScan()
                    val summary = first.toSummary()
                    summary.added shouldBe 2
                    summary.modified shouldBe 0
                    summary.removed shouldBe 0
                    summary.moved shouldBe 0
                    summary.totalBooks shouldBe 2
                    summary.errors shouldBe 0
                }
            }
        }

        test("progress events are emitted across scan phases") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 1) }
                }.use { fixture ->
                    val (scanner, eventBus) = newScanner(fixture)
                    scanner.runFullScan()

                    // ANALYZING now ticks more than once (initial seed + throttled mid-loop
                    // emits + an unconditional final tick carrying running aggregates), so we
                    // assert each phase is present rather than an exact multiset.
                    val phases =
                        eventBus.replayCache
                            .filterIsInstance<ScanEvent.Progress>()
                            .map { it.phase }
                            .distinct()
                    phases shouldContainExactlyInAnyOrder
                        listOf(
                            com.calypsan.listenup.api.dto.scanner.ScanPhase.WALKING,
                            com.calypsan.listenup.api.dto.scanner.ScanPhase.GROUPING,
                            com.calypsan.listenup.api.dto.scanner.ScanPhase.ANALYZING,
                            com.calypsan.listenup.api.dto.scanner.ScanPhase.DIFFING,
                        )
                }
            }
        }

        test("runFullScan emits ScanResult with scope=Full to scanResultBus") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 1) }
                }.use { fixture ->
                    val bus = MutableSharedFlow<ScanResult>(replay = 1)
                    val (scanner, _) = newScanner(fixture, scanResultBus = bus)

                    scanner.runFullScan()

                    val result = bus.replayCache.first()
                    result.scope shouldBe ScanScope.Full
                }
            }
        }

        test("a superseded scanner does NOT publish its full-scan result to scanResultBus (A8)") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 1) }
                }.use { fixture ->
                    val bus = MutableSharedFlow<ScanResult>(replay = 1)
                    val (scanner, _) = newScanner(fixture, scanResultBus = bus)

                    // The bundle was rebuilt under this scanner (a folder was added): a scan that was
                    // already in flight walked a stale folder set. Its Full result must be dropped, or
                    // BookPersister would sweep the newly-added folder's books.
                    scanner.markSuperseded()
                    val result = scanner.runFullScan()

                    // The scan still computes a result (returned to any synchronous caller) …
                    result.scope shouldBe ScanScope.Full
                    // … but it must NOT reach the persister bus.
                    bus.replayCache.shouldBeEmpty()
                }
            }
        }

        test("a book with no playable track is skipped, not ingested") {
            runTest {
                audioLibrary {
                    book("Author/Real Book") { tracks(count = 1) }
                    // Image-only folder — yields a candidate with zero audio tracks.
                    book("Author/Coverless") { cover() }
                }.use { fixture ->
                    val (scanner, _) = newScanner(fixture)

                    val result = scanner.runFullScan()

                    result.books.map { it.candidate.rootRelPath } shouldBe listOf("Author/Real Book")
                    result.errors.size shouldBe 1
                    val error = result.errors.single().shouldBeInstanceOf<ScanError.FileUnreadable>()
                    // The error names the failing book's own directory, not the library root.
                    error.path shouldBe fixture.root.resolve("Author/Coverless").toString()
                }
            }
        }

        test("a book with an unreadable primary file stays in books with hasScanWarning") {
            runTest {
                audioLibrary {
                    // 64 bytes of zeroes — past the parser's minimum-header threshold,
                    // but no magic matches, so the parser rejects it as unsupported.
                    book("Author/Damaged") { audio("01.mp3", sizeBytes = 64) }
                }.use { fixture ->
                    val (scanner, _) = newScanner(fixture)

                    val result = scanner.runFullScan()

                    val book = result.books.single { it.candidate.rootRelPath == "Author/Damaged" }
                    book.hasScanWarning shouldBe true
                    book.embeddedStatus.shouldNotBeNull()
                }
            }
        }

        test("a scan over a library with one unreadable file completes normally") {
            runTest {
                audioLibrary {
                    book("Author/Good A") { tracks(count = 1) }
                    book("Author/Good B") { tracks(count = 2) }
                    // Image-only folder — fails analysis, must not abort the scan.
                    book("Author/Bad") { cover() }
                }.use { fixture ->
                    val (scanner, _) = newScanner(fixture)

                    // the scan completes and reports the failure as an error, never throwing.
                    val result = scanner.runFullScan()

                    result.books.map { it.candidate.rootRelPath } shouldContainExactlyInAnyOrder
                        listOf("Author/Good A", "Author/Good B")
                    result.errors.size shouldBe 1
                }
            }
        }

        test("runIncremental emits ScanResult with scope=Subtree to scanResultBus") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 1) }
                }.use { fixture ->
                    val bus = MutableSharedFlow<ScanResult>(replay = 1)
                    val (scanner, _) = newScanner(fixture, scanResultBus = bus)
                    scanner.runFullScan() // populate lastResult

                    scanner.runIncremental(Path(fixture.root.resolve("Author/Title").toString()))

                    val result = bus.replayCache.last()
                    (result.scope is ScanScope.Subtree) shouldBe true
                    (result.scope as ScanScope.Subtree).rootRelPath shouldBe "Author/Title"
                }
            }
        }

        test("runIncremental stamps the result with its OWN correlationId, not the previous full scan's (#703)") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 1) }
                }.use { fixture ->
                    val bus = MutableSharedFlow<ScanResult>(replay = 1)
                    val counter = AtomicLong(0)
                    val (scanner, _) =
                        newScanner(
                            fixture,
                            correlationIdFactory = { "scan-${counter.incrementAndGet()}" },
                            scanResultBus = bus,
                        )
                    scanner.runFullScan() // scan-1

                    scanner.runIncremental(Path(fixture.root.resolve("Author/Title").toString())) // scan-2

                    // Regression: `lastResult?.copy(...)` inherited the prior full scan's correlationId,
                    // so the incremental spooled to scan-2 but cleared/reported scan-1 — leaking the
                    // real spool dir and mis-keying ScanEvent.Completed.
                    withClue("incremental result must carry its own correlationId (scan-2), not inherit scan-1") {
                        bus.replayCache.last().correlationId shouldBe "scan-2"
                    }
                }
            }
        }

        test("runIncremental does not crash when a folder has a null rootPath") {
            runTest {
                audioLibrary {
                    book("Author/Title") { tracks(count = 1) }
                }.use { fixture ->
                    // A library with one real folder and one null-rootPath (redacted) folder.
                    val library =
                        testLibrary(folders = listOf(fixture.root.toString())).copy(
                            folders =
                                listOf(
                                    LibraryFolderRef(FolderId("test-lib-1-folder-0"), fixture.root.toString()),
                                    LibraryFolderRef(FolderId("redacted-folder"), null),
                                ),
                        )
                    val eventBus = MutableSharedFlow<ScanEvent>(replay = 64, extraBufferCapacity = 64)
                    val scanner =
                        Scanner(
                            library = library,
                            metadataReader = AbsMetadataReader(contractJson),
                            embeddedMetadataParser = noOpEmbeddedParser(),
                            eventBus = eventBus,
                            scanResultBus = MutableSharedFlow(replay = 1),
                        )
                    scanner.runFullScan()
                    val bookRoot = Path(fixture.root.resolve("Author/Title").toString())

                    // If runIncremental throws, the test fails — that's the assertion.
                    scanner.runIncremental(bookRoot)
                }
            }
        }

        test("incremental prefix: when an owning folder is found, bookRoot is under folderRoot so relativeTo == nio relativize") {
            val folderRoot = Path("/lib/audiobooks")
            val bookRoot = Path("/lib/audiobooks/Author/Title")
            bookRoot.isUnder(folderRoot) shouldBe true
            bookRoot.relativeTo(folderRoot) shouldBe "Author/Title"
        }
    })

private fun newScanner(
    fixture: AudioLibraryFixture,
    correlationIdFactory: () -> String = { "test-correlation-id" },
    scanResultBus: MutableSharedFlow<ScanResult> = MutableSharedFlow(replay = 1),
): Pair<Scanner, MutableSharedFlow<ScanEvent>> {
    val eventBus = MutableSharedFlow<ScanEvent>(replay = 64, extraBufferCapacity = 64)
    val scanner =
        Scanner(
            library = testLibrary(folders = listOf(fixture.root.toString())),
            metadataReader = AbsMetadataReader(contractJson),
            embeddedMetadataParser = noOpEmbeddedParser(),
            eventBus = eventBus,
            scanResultBus = scanResultBus,
            correlationIdFactory = correlationIdFactory,
        )
    return scanner to eventBus
}

/**
 * Empty parser registry — synthetic placeholder audio files in scanner tests
 * are zero-byte, so every detect call would fall to the size guard or
 * unrecognised-magic path. Embedded enrichment integration coverage lives in
 * `AnalyzerEnrichmentTest` with on-disk MP3 fixtures.
 */
private fun noOpEmbeddedParser(): com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser =
    com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser(
        detector =
            com.calypsan.listenup.server.embeddedmeta
                .AudioFormatDetector(),
        parsers = emptyList(),
    )
