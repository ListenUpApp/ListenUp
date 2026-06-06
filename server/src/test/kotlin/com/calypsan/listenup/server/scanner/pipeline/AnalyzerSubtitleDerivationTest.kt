package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.contractJson
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies [TitleSubtitleSplitter] integration in [Analyzer.compose]:
 * when no explicit subtitle exists in any source, the resolved title is
 * split on `": "` to derive a subtitle; explicit sources always suppress
 * the split.
 */
class AnalyzerSubtitleDerivationTest :
    FunSpec({

        val metadataReader = AbsMetadataReader(contractJson)
        val embeddedParser =
            EmbeddedMetadataParser(
                detector = AudioFormatDetector(),
                parsers = listOf(Mp3Parser()),
            )

        test("derives subtitle from an embedded colon title when no explicit subtitle") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Mistborn"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TIT2", "Mistborn: The Final Empire") }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudio("$rel/01.mp3", audioBytes)
                    val candidate = candidateAt(rel, audioPath)

                    val book =
                        Analyzer(fixture.root, metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "Mistborn"
                    book.subtitle shouldBe "The Final Empire"
                }
            }
        }

        test("an explicit embedded subtitle (TIT3) suppresses the title split") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Mistborn"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Mistborn: The Final Empire")
                                textFrame("TIT3", "An Epic")
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudio("$rel/01.mp3", audioBytes)
                    val candidate = candidateAt(rel, audioPath)

                    val book =
                        Analyzer(fixture.root, metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    // TIT3 is explicit — the colon title must NOT be split
                    book.title shouldBe "Mistborn: The Final Empire"
                    book.subtitle shouldBe "An Epic"
                }
            }
        }

        test("a volume-token colon title is left whole") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Dune"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TIT2", "Dune: 2") }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudio("$rel/01.mp3", audioBytes)
                    val candidate = candidateAt(rel, audioPath)

                    val book =
                        Analyzer(fixture.root, metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    // "2" matches the volume-token guard → left whole, no subtitle
                    book.title shouldBe "Dune: 2"
                    book.subtitle.shouldBeNull()
                }
            }
        }
    })

private fun candidateAt(
    rel: String,
    audioPath: Path,
): CandidateBook =
    CandidateBook(
        rootRelPath = rel,
        isFile = false,
        files =
            listOf(
                FileEntry(
                    relPath = "$rel/${audioPath.fileName}",
                    name = audioPath.fileName.toString(),
                    ext =
                        audioPath.fileName
                            .toString()
                            .substringAfterLast('.', "")
                            .lowercase(),
                    size = Files.size(audioPath),
                    mtimeMs = 0,
                    inode = null,
                    fileType = FileType.AUDIO,
                ),
            ),
    )

private fun Path.writeAudio(
    relPath: String,
    bytes: ByteArray,
): Path {
    val target = resolve(relPath)
    Files.createDirectories(target.parent)
    Files.write(target, bytes)
    return target
}
