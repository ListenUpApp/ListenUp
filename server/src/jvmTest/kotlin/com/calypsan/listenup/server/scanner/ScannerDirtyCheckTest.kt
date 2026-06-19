@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.testing.testLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the dirty-check optimisation in [Scanner.collectAnalyzed]:
 *
 * - A second full scan over an **unchanged** library must not invoke
 *   [EmbeddedMetadataParser.parse] again for any file whose
 *   (inode, mtimeMs, size) fingerprint is identical to the previous
 *   scan's result.
 *
 * - When exactly **one** book's files change (mtime bumped, content
 *   rewritten), only that book is re-parsed on the next scan; all others
 *   are served from the cached [AnalyzedBook].
 *
 * A counting wrapper around [EmbeddedMetadataParser] drives both
 * assertions — it delegates every call to the real parser so the result is
 * correct, while incrementing a counter that the test reads.
 */
class ScannerDirtyCheckTest :
    FunSpec({

        // Unchanged rescan: parse count on the second scan should be zero.
        // Before the dirty-check is implemented this fails because every
        // candidate is re-analyzed regardless.
        test("unchanged rescan skips embedded-parse for every book") {
            runTest {
                audioLibrary {}.use { fixture ->
                    seedRealMp3Library(fixture)

                    val counter = AtomicInteger(0)
                    val (scanner, _) = newCountingScanner(fixture, counter)

                    // First scan: parses are expected (one per book primary audio file).
                    val first = scanner.runFullScan()
                    first.books.size shouldBe 3
                    val firstCount = counter.get()
                    firstCount shouldBeGreaterThanOrEqual 3

                    // Reset counter, then rescan the identical library.
                    counter.set(0)
                    val second = scanner.runFullScan()
                    second.books.size shouldBe 3

                    // Dirty-check: every fingerprint is unchanged → no parse calls.
                    counter.get() shouldBeExactly 0
                }
            }
        }

        // One-book-changed rescan: only the modified book triggers a re-parse.
        // The two untouched books must not be re-parsed.
        test("modified-file rescan re-parses only the changed book") {
            runTest {
                audioLibrary {}.use { fixture ->
                    seedRealMp3Library(fixture)

                    val counter = AtomicInteger(0)
                    val (scanner, _) = newCountingScanner(fixture, counter)

                    // First scan establishes the baseline.
                    scanner.runFullScan()
                    counter.set(0)

                    // Overwrite the audio file in "Author2/Title Two" so its
                    // size and mtime change, invalidating the fingerprint.
                    val changedFile = fixture.root.resolve("Author2/Title Two/01.mp3")
                    Files.write(
                        changedFile,
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Updated Title Two")
                                textFrame("TPE1", "Updated Author Two")
                            }
                            mpegFrames(durationSeconds = 2)
                        },
                    )
                    // Bump mtime explicitly to ensure the Walker sees a changed mtimeMs.
                    changedFile.toFile().setLastModified(System.currentTimeMillis() + 5_000)

                    // Second scan: only the changed book should be re-parsed.
                    val second = scanner.runFullScan()
                    second.books.size shouldBe 3

                    // Author2/Title Two has 1 primary audio file → exactly 1 re-parse.
                    // Author1/Title One and Author3/Title Three serve from cache → 0 parses.
                    counter.get() shouldBeExactly 1
                }
            }
        }
    })

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Seeds a 3-book fixture with real MP3 content so the [EmbeddedMetadataParser]
 * actually invokes [Mp3Parser] and increments the counter. Zero-byte
 * placeholders would be rejected by the size guard before reaching the
 * parser, making the parse count permanently 0 regardless of caching.
 */
private fun seedRealMp3Library(fixture: AudioLibraryFixture) {
    fixture.book("Author1/Title One") {
        writeBytesTo(
            root,
            "01.mp3",
            buildMp3File {
                id3v2(version = 4) {
                    textFrame("TIT2", "Title One")
                    textFrame("TPE1", "Author One")
                }
                mpegFrames(durationSeconds = 1)
            },
        )
    }
    fixture.book("Author2/Title Two") {
        writeBytesTo(
            root,
            "01.mp3",
            buildMp3File {
                id3v2(version = 4) {
                    textFrame("TIT2", "Title Two")
                    textFrame("TPE1", "Author Two")
                }
                mpegFrames(durationSeconds = 1)
            },
        )
    }
    fixture.book("Author3/Title Three") {
        writeBytesTo(
            root,
            "01.mp3",
            buildMp3File {
                id3v2(version = 4) {
                    textFrame("TIT2", "Title Three")
                    textFrame("TPE1", "Author Three")
                }
                mpegFrames(durationSeconds = 1)
            },
        )
    }
}

private fun writeBytesTo(
    bookRoot: java.nio.file.Path,
    name: String,
    bytes: ByteArray,
) {
    Files.write(bookRoot.resolve(name), bytes)
}

/**
 * Builds a [Scanner] backed by a [CountingEmbeddedMetadataParser] that
 * increments [counter] on every [EmbeddedMetadataParser.parse] call
 * while delegating to a real [Mp3Parser]-backed parser for correct results.
 */
private fun newCountingScanner(
    fixture: AudioLibraryFixture,
    counter: AtomicInteger,
): Pair<Scanner, MutableSharedFlow<ScanEvent>> {
    val eventBus = MutableSharedFlow<ScanEvent>(replay = 64, extraBufferCapacity = 64)
    val realParser =
        EmbeddedMetadataParser(
            detector = AudioFormatDetector(),
            parsers = listOf(Mp3Parser()),
        )
    val countingParser = CountingEmbeddedMetadataParser(realParser, counter)
    val scanner =
        Scanner(
            library = testLibrary(folders = listOf(fixture.root.toString())),
            metadataReader = AbsMetadataReader(contractJson),
            embeddedMetadataParser = countingParser,
            eventBus = eventBus,
            scanResultBus = MutableSharedFlow<ScanResult>(replay = 1),
            correlationIdFactory = { "test-correlation-id" },
        )
    return scanner to eventBus
}

/**
 * Wraps a real [EmbeddedMetadataParser], incrementing [counter] on each
 * [parse] call before delegating. Lives in the `scanner` package to satisfy
 * the `internal` visibility of [EmbeddedMetadataParser] without changes to
 * the production class hierarchy.
 */
internal class CountingEmbeddedMetadataParser(
    private val delegate: EmbeddedMetadataParser,
    private val counter: AtomicInteger,
) : EmbeddedMetadataParser(
        detector = AudioFormatDetector(),
        parsers = emptyList(),
    ) {
    override suspend fun parse(path: Path): AppResult<EmbeddedAudioMetadata> {
        counter.incrementAndGet()
        return delegate.parse(path)
    }
}
