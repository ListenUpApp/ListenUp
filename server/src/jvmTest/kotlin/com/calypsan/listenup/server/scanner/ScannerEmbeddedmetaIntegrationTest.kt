@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ChangeEventDto
import com.calypsan.listenup.api.dto.scanner.CoverSource
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.dto.scanner.MetadataStatus
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.testing.testLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end integration coverage: real audio bytes through Walker →
 * Grouper → Analyzer → Differ, exercising the full enrichment
 * pipeline.
 *
 * Three deliberate book fixtures probe distinct contracts:
 *
 *  - **Book 1** — embedded-only: MP3 carries title, author, and APIC
 *    artwork. No `metadata.json`, no filesystem cover. Asserts that
 *    embedded tags drive the resolved view and embedded artwork becomes
 *    the cover.
 *  - **Book 2** — sidecar overlay: MP3 has embedded tags AND a
 *    `metadata.json` overlay. Asserts ABS-metadata precedence over
 *    embedded for the resolved view, while [embedded] is preserved
 *    verbatim and [sources] records both contributing layers.
 *  - **Book 3** — filesystem cover: MP3 has no APIC; a `cover.jpg`
 *    sits beside it. Asserts filesystem cover wins (audio metadata
 *    precedence applies to text fields, not artwork — file on disk
 *    reflects user intent).
 *
 * Plus a Differ stability check: a second `runFullScan()` against the
 * unchanged fixture must emit zero `Modified` events. Proves
 * [com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata] /
 * [com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork] carry
 * value equality through the pipeline (the `EmbeddedArtwork.equals`
 * override using `bytes.contentEquals` is the load-bearing piece).
 */
class ScannerEmbeddedmetaIntegrationTest :
    FunSpec({

        test("Walker→Grouper→Analyzer→Differ enriches books from real embedded MP3 metadata") {
            audioLibrary {}.use { fixture ->
                runTest {
                    seedThreeBookLibrary(fixture)
                    val (scanner, eventBus) = newScanner(fixture)

                    val first = scanner.runFullScan()

                    first.books.size shouldBe 3
                    first.errors shouldBe emptyList()

                    // Book 1 — embedded-only.
                    val book1 = first.books.single { it.candidate.rootRelPath == "Author1/Title One" }
                    book1.title shouldBe "The Embedded Title"
                    book1.authors shouldBe listOf("Author One")
                    book1.embeddedStatus shouldBe MetadataStatus.Available
                    book1.embedded?.chapters?.size shouldBe 2
                    val cover1 = book1.cover.shouldBeInstanceOf<CoverSource.Embedded>()
                    cover1.artwork.mime shouldBe "image/jpeg"
                    book1.fieldProvenance[BookField.TITLE]?.kind shouldBe FieldSourceKind.EMBEDDED

                    // Book 2 — sidecar overlay overrides embedded for the resolved view;
                    // embedded payload survives verbatim on `embedded`.
                    val book2 = first.books.single { it.candidate.rootRelPath == "Author2/Title Two" }
                    book2.title shouldBe "Sidecar Title Two"
                    book2.authors shouldBe listOf("Sidecar Author Two")
                    book2.embedded?.tags?.title shouldBe "Embedded Title Two"
                    book2.embedded?.tags?.authors shouldBe listOf("Embedded Author Two")
                    // metadata.json wins the resolved title/authors; the embedded values survive as raw
                    // signal on `book2.embedded` (asserted above), winner-based provenance records ABS.
                    book2.fieldProvenance[BookField.TITLE]?.kind shouldBe FieldSourceKind.ABS_METADATA

                    // Book 3 — filesystem cover wins over absent embedded artwork.
                    val book3 = first.books.single { it.candidate.rootRelPath == "Author3/Title Three" }
                    book3.title shouldBe "Embedded Three"
                    val cover3 = book3.cover.shouldBeInstanceOf<CoverSource.Filesystem>()
                    cover3.file.name shouldBe "cover.jpg"
                    book3.embedded?.artwork shouldBe null

                    // Aggregate counters reflect the fixture: 3 parsed, ≥1 with chapters,
                    // exactly 1 with embedded artwork (only book 1).
                    val summary = first.toSummary()
                    summary.totalBooks shouldBe 3
                    summary.embedded.parsed shouldBe 3
                    summary.embedded.withChapters shouldBeGreaterThanOrEqual 1
                    summary.embedded.withArtwork shouldBe 1
                    summary.embedded.unsupported shouldBe 0
                    summary.embedded.parseErrors shouldBe 0

                    // Differ stability: a second full scan over the unchanged fixture must
                    // produce zero Modified events. This is the load-bearing property —
                    // any reorder / non-deterministic hashing in EmbeddedAudioMetadata
                    // would surface as spurious Modified emissions on every re-scan.
                    eventBus.resetReplayCache()
                    val second = scanner.runFullScan()
                    second.changes shouldBe emptyList()
                    eventBus.replayCache.count { it is ScanEvent.Change } shouldBe 0
                }
            }
        }

        test("scan emits ANALYZING progress enriched with authors, duration, recent books, current file") {
            audioLibrary {}.use { fixture ->
                runTest {
                    seedThreeBookLibrary(fixture)
                    val (scanner, eventBus) = newScanner(fixture)

                    scanner.runFullScan()

                    // The unconditional final ANALYZING/DIFFING tick guarantees the last
                    // batch's aggregates land regardless of the 200ms throttle, so the
                    // assertion is deterministic even for a fast 3-book fixture.
                    val enriched =
                        eventBus.replayCache
                            .filterIsInstance<ScanEvent.Progress>()
                            .last { it.booksAnalyzed > 0 }
                    enriched.authorsMatched shouldBeGreaterThan 0
                    enriched.totalDurationMs shouldBeGreaterThan 0L
                    enriched.recentBooks.shouldNotBeEmpty()
                    enriched.currentFile.shouldNotBeNull()
                }
            }
        }

        test("scan progress carries booksTotal (candidate count) from ANALYZING onward") {
            audioLibrary {}.use { fixture ->
                runTest {
                    seedThreeBookLibrary(fixture)
                    val (scanner, eventBus) = newScanner(fixture)

                    scanner.runFullScan()

                    // The fixture groups to exactly 3 candidate books, all of which
                    // analyze successfully — so the final Progress tick must carry
                    // booksTotal == 3 and, with every candidate analyzed, booksTotal
                    // == booksAnalyzed.
                    val last =
                        eventBus.replayCache
                            .filterIsInstance<ScanEvent.Progress>()
                            .last { it.booksAnalyzed > 0 }
                    last.booksTotal shouldBe 3
                    last.booksTotal shouldBe last.booksAnalyzed
                }
            }
        }

        test("lastResult retains no artwork bytes after a full scan") {
            audioLibrary {}.use { fixture ->
                runTest {
                    seedThreeBookLibrary(fixture)
                    val (scanner, _) = newScanner(fixture)

                    scanner.runFullScan()

                    val last = scanner.lastResult()
                    last.shouldNotBeNull()

                    // No CoverSource.Embedded in lastResult.books — embedded artwork must be stripped.
                    val embeddedCovers = last.books.filter { it.cover is CoverSource.Embedded }
                    embeddedCovers shouldBe emptyList()

                    // No artwork bytes in the embedded field of any book.
                    val booksWithArtwork = last.books.filter { it.embedded?.artwork != null }
                    booksWithArtwork shouldBe emptyList()

                    // Also check changes — Added/Modified/Moved must have no artwork bytes.
                    val changesWithEmbeddedCover =
                        last.changes.filter { change ->
                            val book =
                                when (change) {
                                    is ChangeEventDto.Added -> change.book
                                    is ChangeEventDto.Modified -> change.book
                                    is ChangeEventDto.Moved -> change.book
                                    is ChangeEventDto.Removed -> null
                                }
                            book?.cover is CoverSource.Embedded
                        }
                    changesWithEmbeddedCover shouldBe emptyList()

                    val changesWithArtwork =
                        last.changes.filter { change ->
                            val book =
                                when (change) {
                                    is ChangeEventDto.Added -> change.book
                                    is ChangeEventDto.Modified -> change.book
                                    is ChangeEventDto.Moved -> change.book
                                    is ChangeEventDto.Removed -> null
                                }
                            book?.embedded?.artwork != null
                        }
                    changesWithArtwork shouldBe emptyList()
                }
            }
        }

        test("second full scan over unchanged artwork-bearing library emits no Modified changes") {
            audioLibrary {}.use { fixture ->
                runTest {
                    seedThreeBookLibrary(fixture)
                    val (scanner, _) = newScanner(fixture)

                    scanner.runFullScan() // first scan: builds lastResult (stripped)
                    val second = scanner.runFullScan() // second scan: diffs against stripped lastResult

                    // No Modified events — stripping both diff sides consistently means
                    // artwork-bearing books that have not changed are still structurally equal.
                    val modified = second.changes.filterIsInstance<ChangeEventDto.Modified>()
                    modified shouldBe emptyList()
                    second.changes shouldBe emptyList()
                }
            }
        }
    })

private fun seedThreeBookLibrary(fixture: AudioLibraryFixture) {
    fakeJpeg().also { jpegBytes ->
        fixture.book("Author1/Title One") {
            writeBytes(
                root,
                "01.mp3",
                buildMp3File {
                    id3v2(version = 4) {
                        textFrame("TIT2", "The Embedded Title")
                        textFrame("TPE1", "Author One")
                        chapFrame("ch1", startMs = 0, endMs = 30_000, title = "Chapter One")
                        chapFrame("ch2", startMs = 30_000, endMs = 60_000, title = "Chapter Two")
                        apicFrame(mime = "image/jpeg", pictureType = 3, description = "Cover", imageBytes = jpegBytes)
                    }
                    mpegFrames(durationSeconds = 1)
                },
            )
        }
    }

    fixture.book("Author2/Title Two") {
        writeBytes(
            root,
            "01.mp3",
            buildMp3File {
                id3v2(version = 4) {
                    textFrame("TIT2", "Embedded Title Two")
                    textFrame("TPE1", "Embedded Author Two")
                }
                mpegFrames(durationSeconds = 1)
            },
        )
        metadataJson(
            """{"title":"Sidecar Title Two","authors":["Sidecar Author Two"]}""",
        )
    }

    fixture.book("Author3/Title Three") {
        writeBytes(
            root,
            "01.mp3",
            buildMp3File {
                id3v2(version = 4) { textFrame("TIT2", "Embedded Three") }
                mpegFrames(durationSeconds = 1)
            },
        )
        writeBytes(root, "cover.jpg", fakeJpeg())
    }
}

private fun writeBytes(
    bookRoot: Path,
    name: String,
    bytes: ByteArray,
) {
    Files.write(bookRoot.resolve(name), bytes)
}

private fun fakeJpeg(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 'J'.code.toByte(), 'F'.code.toByte())

private fun newScanner(fixture: AudioLibraryFixture): Pair<Scanner, MutableSharedFlow<ScanEvent>> {
    val eventBus = MutableSharedFlow<ScanEvent>(replay = 64, extraBufferCapacity = 64)
    val parser =
        EmbeddedMetadataParser(
            detector = AudioFormatDetector(),
            parsers = listOf(Mp3Parser()),
        )
    val scanner =
        Scanner(
            library = testLibrary(folders = listOf(fixture.root.toString())),
            metadataReader = AbsMetadataReader(contractJson),
            embeddedMetadataParser = parser,
            eventBus = eventBus,
            scanResultBus = MutableSharedFlow(replay = 1),
            correlationIdFactory = { "test-correlation-id" },
        )
    return scanner to eventBus
}
