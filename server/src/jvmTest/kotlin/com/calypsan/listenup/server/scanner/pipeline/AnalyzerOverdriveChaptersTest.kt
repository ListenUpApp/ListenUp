package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.BookChapterSource
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.scanner.audioLibrary
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path as NioPath
import kotlinx.io.files.Path

/**
 * End-to-end OverDrive-marker chapter tests: real MP3 bytes with `TXXX:"OverDrive MediaMarkers"`
 * frames → the production parser graph → [OverdriveChapters] via `Analyzer.pickChapters`. Split
 * from [AnalyzerEnrichmentTest] to keep each spec focused.
 */
class AnalyzerOverdriveChaptersTest :
    FunSpec({

        val metadataReader = AbsMetadataReader(contractJson)
        val embeddedParser =
            EmbeddedMetadataParser(
                detector = AudioFormatDetector(),
                parsers = listOf(Mp3Parser()),
            )

        fun markers(vararg entries: Pair<String, String>) =
            buildString {
                append("<Markers>")
                entries.forEach { (n, t) -> append("<Marker><Name>$n</Name><Time>$t</Time></Marker>") }
                append("</Markers>")
            }

        test("single-file OverDrive book: TXXX marker frame becomes chapters end-to-end") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Overdrive Book"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Overdrive Book")
                                txxxFrame("OverDrive MediaMarkers", markers("Chapter 1" to "0:00.000", "Chapter 2" to "0:01.000"))
                            }
                            mpegFrames(durationSeconds = 3)
                        }
                    val f = fixture.root.writeAudioFile("$rel/Book.mp3", audioBytes)
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files = listOf(fileEntry("$rel/Book.mp3", FileType.AUDIO, size = Files.size(f))),
                        )

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.chaptersSource shouldBe BookChapterSource.Overdrive
                    book.chapters shouldHaveSize 2
                    book.chapters[0].title shouldBe "Chapter 1"
                    book.chapters[0].startMs shouldBe 0L
                    book.chapters[1].title shouldBe "Chapter 2"
                    book.chapters[1].startMs shouldBe 1_000L
                }
            }
        }

        test("multi-file OverDrive book: per-track markers stitch with cross-track offsets end-to-end") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Overdrive Multi"
                    val track1 =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Overdrive Multi")
                                txxxFrame("OverDrive MediaMarkers", markers("Chapter 1" to "0:00.000"))
                            }
                            mpegFrames(durationSeconds = 2)
                        }
                    val track2 =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Overdrive Multi")
                                txxxFrame("OverDrive MediaMarkers", markers("Chapter 2" to "0:00.000", "Chapter 3" to "0:01.000"))
                            }
                            mpegFrames(durationSeconds = 3)
                        }
                    val f1 = fixture.root.writeAudioFile("$rel/01.mp3", track1)
                    val f2 = fixture.root.writeAudioFile("$rel/02.mp3", track2)
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    fileEntry("$rel/01.mp3", FileType.AUDIO, size = Files.size(f1)),
                                    fileEntry("$rel/02.mp3", FileType.AUDIO, size = Files.size(f2)),
                                ),
                        )

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.chaptersSource shouldBe BookChapterSource.Overdrive
                    book.chapters shouldHaveSize 3
                    book.chapters.map { it.title } shouldBe listOf("Chapter 1", "Chapter 2", "Chapter 3")
                    book.chapters[0].startMs shouldBe 0L
                    // Track 2's markers are offset by track 1's whole duration — the cross-track stitch.
                    val track1Duration = book.chapters[1].startMs
                    track1Duration shouldBe book.chapters[0].endMs // contiguous
                    (track1Duration > 1_000L) shouldBe true // genuinely offset, not zero
                    // Chapter 3 sits 1s into track 2 (a track-relative gap that survives the offset).
                    book.chapters[2].startMs shouldBe track1Duration + 1_000L
                }
            }
        }
    })

private fun fileEntry(
    relPath: String,
    fileType: FileType,
    size: Long = 0,
): FileEntry =
    FileEntry(
        relPath = relPath,
        name = relPath.substringAfterLast('/'),
        ext = relPath.substringAfterLast('.', "").lowercase(),
        size = size,
        mtimeMs = 0,
        inode = null,
        fileType = fileType,
    )

private fun NioPath.writeAudioFile(
    relPath: String,
    bytes: ByteArray,
): NioPath {
    val target = resolve(relPath)
    Files.createDirectories(target.parent)
    Files.write(target, bytes)
    return target
}
