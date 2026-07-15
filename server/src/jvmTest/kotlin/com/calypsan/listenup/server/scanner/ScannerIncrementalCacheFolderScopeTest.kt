@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.testing.testLibrary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeExactly
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression coverage for finding M9: the incremental fingerprint cache was not folder-scoped.
 *
 * `Scanner.runIncremental` keyed the previous-scan cache by `rootRelPath` alone across EVERY folder.
 * When two folders hold a book at the same relative path (a `Author/Book` in each), `associateBy`
 * kept only the last one — so an incremental over folder A looked up folder B's `AnalyzedBook`,
 * whose fingerprint doesn't match (distinct files → distinct inodes), evicting folder A's genuine
 * cache hit and forcing a needless re-parse (or, on a null-inode NAS, reusing the WRONG analysis).
 *
 * A11 fixed this for full scans by bucketing per folder; M9 extends the same guard to the
 * incremental path.
 */
class ScannerIncrementalCacheFolderScopeTest :
    FunSpec({

        test("an incremental cache is folder-scoped — a same-relpath book in another folder is not consulted") {
            runTest {
                audioLibrary("listenup-m9-a-").use { folderA ->
                    audioLibrary("listenup-m9-b-").use { folderB ->
                        // Identical rootRelPath in BOTH folders → the collision the fix guards against.
                        seedRealMp3Book(folderA, "Author/Book")
                        seedRealMp3Book(folderB, "Author/Book")

                        val counter = AtomicInteger(0)
                        val realParser =
                            EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = listOf(Mp3Parser()))
                        val scanner =
                            Scanner(
                                library =
                                    testLibrary(
                                        folders = listOf(folderA.root.toString(), folderB.root.toString()),
                                    ),
                                metadataReader = AbsMetadataReader(contractJson),
                                embeddedMetadataParser = CountingEmbeddedMetadataParser(realParser, counter),
                                eventBus = MutableSharedFlow<ScanEvent>(replay = 64, extraBufferCapacity = 64),
                                scanResultBus = MutableSharedFlow<ScanResult>(replay = 1),
                                correlationIdFactory = { "m9-corr" },
                            )

                        // Full scan analyses both books and populates lastResult.
                        scanner.runFullScan()
                        counter.set(0)

                        // Folder A's book is untouched, so a folder-scoped cache serves it verbatim
                        // (0 parses). An unscoped cache would consult folder B's same-relpath book,
                        // miss the fingerprint, and re-parse folder A's book.
                        scanner.runIncremental(Path(folderA.root.toString()))

                        counter.get() shouldBeExactly 0
                    }
                }
            }
        }
    })

private fun seedRealMp3Book(
    fixture: AudioLibraryFixture,
    relPath: String,
) {
    fixture.book(relPath) {
        Files.write(
            root.resolve("01.mp3"),
            buildMp3File {
                id3v2(version = 4) {
                    textFrame("TIT2", "Title")
                    textFrame("TPE1", "Author")
                }
                mpegFrames(durationSeconds = 1)
            },
        )
    }
}
