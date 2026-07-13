package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackNumberSource
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.scanner.audioLibrary
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path as NioPath
import kotlinx.io.files.Path

/**
 * Track-ordering behaviour for multi-file books.
 *
 * Embedded TRCK/TPOS tags (ID3v2 TRCK, TPOS) take precedence over filename
 * inference for multi-track candidates — ABS parity. Single-file books are
 * never parsed for ordering (no ordering needed).
 */
class AnalyzerTrackOrderTest :
    FunSpec({

        val metadataReader = AbsMetadataReader(contractJson)
        val embeddedParser =
            EmbeddedMetadataParser(
                detector = AudioFormatDetector(),
                parsers = listOf(Mp3Parser()),
            )

        test("embedded TRCK numbers order tracks when filenames carry no digits") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Multi"
                    // "alpha.mp3" sorts first alphabetically but carries TRCK=2;
                    // "beta.mp3" sorts second but carries TRCK=1.
                    // Correct playback order is [beta, alpha] — embedded wins.
                    val alphaBytes =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TRCK", "2") }
                            mpegFrames(durationSeconds = 1)
                        }
                    val betaBytes =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TRCK", "1") }
                            mpegFrames(durationSeconds = 1)
                        }
                    val alphaPath = fixture.root.writeAudio("$rel/alpha.mp3", alphaBytes)
                    val betaPath = fixture.root.writeAudio("$rel/beta.mp3", betaBytes)
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    trackEntry("$rel/alpha.mp3", Files.size(alphaPath)),
                                    trackEntry("$rel/beta.mp3", Files.size(betaPath)),
                                ),
                        )

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.tracks.map { it.file.name } shouldBe listOf("beta.mp3", "alpha.mp3")
                    book.tracks.first().trackNumber shouldBe 1
                    book.tracks.last().trackNumber shouldBe 2
                    book.tracks.first().trackSource shouldBe TrackNumberSource.METADATA
                    book.tracks.last().trackSource shouldBe TrackNumberSource.METADATA
                }
            }
        }

        test("embedded TPOS disc numbers order tracks across discs") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Multi"
                    // Both files have TRCK=1 but different TPOS — disc 2 file sorts second.
                    val disc1Bytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TRCK", "1")
                                textFrame("TPOS", "1")
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val disc2Bytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TRCK", "1")
                                textFrame("TPOS", "2")
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    // "zeta.mp3" (disc2) alphabetically before "alpha.mp3" (disc1) — embedded order wins.
                    val disc2Path = fixture.root.writeAudio("$rel/zeta.mp3", disc2Bytes)
                    val disc1Path = fixture.root.writeAudio("$rel/alpha.mp3", disc1Bytes)
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    trackEntry("$rel/zeta.mp3", Files.size(disc2Path)),
                                    trackEntry("$rel/alpha.mp3", Files.size(disc1Path)),
                                ),
                        )

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.tracks.map { it.file.name } shouldBe listOf("alpha.mp3", "zeta.mp3")
                    book.tracks.first().discNumber shouldBe 1
                    book.tracks.last().discNumber shouldBe 2
                    book.tracks.first().discSource shouldBe TrackNumberSource.METADATA
                }
            }
        }

        test("filename inference still applies when embedded tags carry no track number") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Multi"
                    // Files named with track digits but no TRCK tag embedded.
                    // Filename inference should win and ordering should be by filename number.
                    val track02Bytes =
                        buildMp3File {
                            id3v2(version = 4) {}
                            mpegFrames(durationSeconds = 1)
                        }
                    val track01Bytes =
                        buildMp3File {
                            id3v2(version = 4) {}
                            mpegFrames(durationSeconds = 1)
                        }
                    val p2 = fixture.root.writeAudio("$rel/02.mp3", track02Bytes)
                    val p1 = fixture.root.writeAudio("$rel/01.mp3", track01Bytes)
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    trackEntry("$rel/02.mp3", Files.size(p2)),
                                    trackEntry("$rel/01.mp3", Files.size(p1)),
                                ),
                        )

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.tracks.map { it.file.name } shouldBe listOf("01.mp3", "02.mp3")
                    book.tracks.first().trackNumber shouldBe 1
                    book.tracks.first().trackSource shouldBe TrackNumberSource.FILENAME
                }
            }
        }

        test("untagged year-prefixed files order 1, 2, 10 (not lexicographic 1, 10, 2)") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/1984"
                    val names = listOf("1984 - 1.mp3", "1984 - 2.mp3", "1984 - 10.mp3")
                    val entries =
                        names.map { name ->
                            val bytes =
                                buildMp3File {
                                    id3v2(version = 4) {}
                                    mpegFrames(durationSeconds = 1)
                                }
                            val p = fixture.root.writeAudio("$rel/$name", bytes)
                            trackEntry("$rel/$name", Files.size(p))
                        }
                    // Provide files in a deliberately jumbled order.
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files = listOf(entries[2], entries[0], entries[1]),
                        )

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.tracks.map { it.file.name } shouldBe
                        listOf("1984 - 1.mp3", "1984 - 2.mp3", "1984 - 10.mp3")
                    book.tracks.map { it.trackNumber } shouldBe listOf(1, 2, 10)
                }
            }
        }

        test("a disc-less file sorts after CD1/CD2 disc folders") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Boxset"
                    // bonus.mp3 lives at the book root (no disc signal); CD1/CD2 files
                    // carry a folder disc. The bonus file must not jump ahead of disc 1.
                    val mk = {
                        buildMp3File {
                            id3v2(version = 4) {}
                            mpegFrames(durationSeconds = 1)
                        }
                    }
                    val bonus = fixture.root.writeAudio("$rel/bonus.mp3", mk())
                    val cd1 = fixture.root.writeAudio("$rel/CD1/01.mp3", mk())
                    val cd2 = fixture.root.writeAudio("$rel/CD2/01.mp3", mk())
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    trackEntry("$rel/bonus.mp3", Files.size(bonus)),
                                    trackEntry("$rel/CD1/01.mp3", Files.size(cd1)),
                                    trackEntry("$rel/CD2/01.mp3", Files.size(cd2)),
                                ),
                        )

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.tracks.map { it.file.name } shouldBe listOf("01.mp3", "01.mp3", "bonus.mp3")
                    book.tracks.map { it.discNumber } shouldBe listOf(1, 2, null)
                }
            }
        }
    })

private fun trackEntry(
    relPath: String,
    size: Long = 0,
): FileEntry =
    FileEntry(
        relPath = relPath,
        name = relPath.substringAfterLast('/'),
        ext = relPath.substringAfterLast('.', "").lowercase(),
        size = size,
        mtimeMs = 0,
        inode = null,
        fileType = FileType.AUDIO,
    )

private fun NioPath.writeAudio(
    relPath: String,
    bytes: ByteArray,
): NioPath {
    val target = resolve(relPath)
    Files.createDirectories(target.parent)
    Files.write(target, bytes)
    return target
}
