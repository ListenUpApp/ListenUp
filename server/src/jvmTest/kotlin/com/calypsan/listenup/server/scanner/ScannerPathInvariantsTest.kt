@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.testing.testLibrary
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

/**
 * Behavioural invariants for the scanner's path handling. These encode the
 * 2026-06-25 regression where a root-level incremental scan
 * (`bookRoot == folderRoot`) rebased every book onto an ABSOLUTE
 * `rootRelPath` — `FileHelpers.relativeTo` returned the absolute path instead
 * of `""` for the equal-paths case. The unit test on `relativeTo`
 * (`FileHelpersTest`) pins the helper; these prove the *mechanism* — a no-op
 * add must never make the scanner treat every book as moved.
 *
 * The fixture deliberately spans depth, apostrophes/spaces, a multi-disc book,
 * and a loose single file at the library root, so the assertions hold across
 * the path shapes the corpus actually contains.
 */
class ScannerPathInvariantsTest :
    FunSpec({

        // --- Invariant A: no scan ever produces an absolute rootRelPath -------

        test("full scan produces only library-relative rootRelPaths") {
            runTest {
                richLibrary().use { fixture ->
                    val (scanner, _) = newScanner(fixture)

                    val result = scanner.runFullScan()

                    result.absoluteRootRelPaths(fixture) shouldBe emptyList()
                }
            }
        }

        test("root-level incremental (bookRoot == folderRoot) produces only relative rootRelPaths") {
            runTest {
                richLibrary().use { fixture ->
                    val (scanner, _) = newScanner(fixture)

                    // THE bug trigger: an incremental scan rooted at the library
                    // folder itself. Pre-fix this rebased every book onto an
                    // absolute path.
                    scanner.runIncremental(Path(fixture.root.toString()))

                    scanner.lastResult().shouldNotBeNull().absoluteRootRelPaths(fixture) shouldBe emptyList()
                }
            }
        }

        test("deep-subtree incremental produces only relative rootRelPaths") {
            runTest {
                richLibrary().use { fixture ->
                    val (scanner, _) = newScanner(fixture)

                    val subtree = Path(fixture.root.resolve("Sanderson/Stormlight/The Way of Kings").toString())
                    scanner.runIncremental(subtree)

                    scanner.lastResult().shouldNotBeNull().absoluteRootRelPaths(fixture) shouldBe emptyList()
                }
            }
        }

        // --- Invariant B: re-scan is idempotent (the headline guard) ----------

        test("a second full scan over an unchanged tree emits zero changes") {
            runTest {
                richLibrary().use { fixture ->
                    val (scanner, eventBus) = newScanner(fixture)
                    scanner.runFullScan()
                    eventBus.resetReplayCache()

                    val result = scanner.runFullScan()

                    withClue("an unchanged re-scan must produce no Added/Modified/Removed/Moved") {
                        result.changes shouldBe emptyList()
                        eventBus.replayCache.count { it is ScanEvent.Change } shouldBe 0
                    }
                }
            }
        }

        test("a root-level incremental over an unchanged tree emits zero changes") {
            runTest {
                richLibrary().use { fixture ->
                    val (scanner, eventBus) = newScanner(fixture)
                    scanner.runFullScan()
                    eventBus.resetReplayCache()

                    // Encodes the user's symptom directly: nothing changed, but a
                    // root-rooted incremental made every book look moved.
                    scanner.runIncremental(Path(fixture.root.toString()))

                    withClue("a no-op root incremental must not report a single change") {
                        eventBus.replayCache.count { it is ScanEvent.Change } shouldBe 0
                    }
                }
            }
        }

        // --- Invariant C: incremental matches full ----------------------------

        test("root-level incremental yields the same rootRelPath set as a full scan") {
            runTest {
                richLibrary().use { fixture ->
                    val full = newScanner(fixture).first.runFullScan()
                    val fullPaths = full.books.map { it.candidate.rootRelPath }

                    val (incremental, _) = newScanner(fixture)
                    incremental.runIncremental(Path(fixture.root.toString()))
                    val incrementalPaths =
                        incremental
                            .lastResult()
                            .shouldNotBeNull()
                            .books
                            .map { it.candidate.rootRelPath }

                    incrementalPaths shouldContainExactlyInAnyOrder fullPaths
                }
            }
        }

        test("a deep-subtree incremental reproduces that book's full-scan rootRelPath") {
            runTest {
                richLibrary().use { fixture ->
                    val full = newScanner(fixture).first.runFullScan()
                    val expected =
                        full.books
                            .single { it.title == "The Way of Kings" }
                            .candidate.rootRelPath

                    val (incremental, _) = newScanner(fixture)
                    incremental.runIncremental(
                        Path(fixture.root.resolve("Sanderson/Stormlight/The Way of Kings").toString()),
                    )
                    val actual =
                        incremental
                            .lastResult()
                            .shouldNotBeNull()
                            .books
                            .single { it.title == "The Way of Kings" }
                            .candidate.rootRelPath

                    actual shouldBe expected
                }
            }
        }
    })

/**
 * A library spanning the path shapes the real corpus contains: nested
 * author/series/title depth, apostrophes and spaces, a multi-disc book, and a
 * loose single file directly at the library root.
 */
private fun richLibrary(): AudioLibraryFixture =
    audioLibrary {
        book("Sanderson/Stormlight/The Way of Kings") { tracks(count = 2) }
        book("John O'Donohue/Anam Cara") { tracks(count = 1) }
        book("Sanderson/Multi Disc Saga") {
            disc("CD1") { audio("01 - Track.mp3") }
            disc("CD2") { audio("01 - Track.mp3") }
        }
        audio("Loose Single.m4b")
    }

/**
 * The `rootRelPath`s in this result that are NOT library-relative — absolute
 * paths, or any path leaking the fixture's temp-dir prefix. Asserting this is
 * `emptyList()` names the offenders directly on failure.
 */
private fun ScanResult.absoluteRootRelPaths(fixture: AudioLibraryFixture): List<String> {
    val absolutePrefix = fixture.root.toString()
    return books
        .map { it.candidate.rootRelPath }
        .filter { it.startsWith("/") || it.contains(absolutePrefix) }
}

private fun newScanner(
    fixture: AudioLibraryFixture,
    scanResultBus: MutableSharedFlow<ScanResult> = MutableSharedFlow(replay = 1),
): Pair<Scanner, MutableSharedFlow<ScanEvent>> {
    val eventBus = MutableSharedFlow<ScanEvent>(replay = 64, extraBufferCapacity = 64)
    val scanner =
        Scanner(
            library = testLibrary(folders = listOf(fixture.root.toString())),
            metadataReader = AbsMetadataReader(contractJson),
            embeddedMetadataParser = EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList()),
            eventBus = eventBus,
            scanResultBus = scanResultBus,
            correlationIdFactory = { "test-correlation-id" },
        )
    return scanner to eventBus
}
