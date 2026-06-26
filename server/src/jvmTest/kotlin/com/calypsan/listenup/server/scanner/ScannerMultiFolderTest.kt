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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files

/**
 * Regression coverage for the multi-folder scan-corruption bug.
 *
 * When a library has multiple root folders, books in non-first folders were
 * flagged [hasScanWarning] = true because [Analyzer] reconstructed absolute
 * paths as `Path(firstFolderRoot, relPath)` for all candidates, regardless of
 * which folder the file was walked from. Files in the second (or later) folder
 * therefore produced a nonexistent absolute path, causing
 * [EmbeddedMetadataParser] to throw an IOException that surfaces as a
 * [MetadataStatus.ParseError] and sets [hasScanWarning].
 *
 * The fix walks and groups each folder independently and creates an [Analyzer]
 * anchored to each folder's own root, so `Path(folderRoot, relPath)` always
 * resolves to the real file regardless of folder order.
 */
class ScannerMultiFolderTest :
    FunSpec({

        test("books in the second library folder have hasScanWarning == false after a full scan") {
            runTest {
                audioLibrary("listenup-folder-a-").use { folderA ->
                    audioLibrary("listenup-folder-b-").use { folderB ->
                        // Seed each folder with a real parseable MP3.
                        val mp3Bytes =
                            buildMp3File {
                                id3v2(version = 4) { textFrame("TIT2", "A Multi-Folder Book") }
                                mpegFrames(durationSeconds = 1)
                            }
                        val bookARoot = folderA.book("Author A/Book A")
                        Files.write(bookARoot.resolve("track.mp3"), mp3Bytes)
                        val bookBRoot = folderB.book("Author B/Book B")
                        Files.write(bookBRoot.resolve("track.mp3"), mp3Bytes)

                        val scanner =
                            Scanner(
                                library =
                                    testLibrary(
                                        folders =
                                            listOf(
                                                folderA.root.toString(),
                                                folderB.root.toString(),
                                            ),
                                    ),
                                metadataReader = AbsMetadataReader(contractJson),
                                embeddedMetadataParser =
                                    EmbeddedMetadataParser(
                                        detector = AudioFormatDetector(),
                                        parsers = listOf(Mp3Parser()),
                                    ),
                                eventBus = MutableSharedFlow<ScanEvent>(replay = 64, extraBufferCapacity = 64),
                                scanResultBus = MutableSharedFlow<ScanResult>(replay = 1),
                                correlationIdFactory = { "test-correlation-id" },
                            )

                        val result = scanner.runFullScan()

                        result.books.size shouldBe 2
                        result.errors.shouldBeEmpty()

                        val bookA =
                            result.books.single { it.candidate.rootRelPath == "Author A/Book A" }
                        val bookB =
                            result.books.single { it.candidate.rootRelPath == "Author B/Book B" }

                        // Pre-fix: bookB.hasScanWarning was true because Analyzer computed
                        // Path(folderA_root, "Author B/Book B/track.mp3") — a path that does not
                        // exist — causing an IOException → ParseError.
                        bookA.hasScanWarning shouldBe false
                        bookB.hasScanWarning shouldBe false
                    }
                }
            }
        }
    })
