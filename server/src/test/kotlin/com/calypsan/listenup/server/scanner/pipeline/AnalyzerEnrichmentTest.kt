package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.CoverSource
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.MetadataSource
import com.calypsan.listenup.api.dto.scanner.MetadataStatus
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.scanner.audioLibrary
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end Analyzer + EmbeddedMetadataParser integration tests.
 *
 * Drives the real production parser graph (`AudioFormatDetector` + `Mp3Parser`)
 * against synthetic on-disk MP3 bytes built via `buildMp3File`. Filesystem-cover
 * tests use the same byte-level fixture so the parser succeeds; only the
 * cover-source assertion changes.
 *
 * Existing Analyzer behavior under fake-FileEntry-only paths lives in
 * [AnalyzerTest], which constructs the parser with an empty registry.
 */
class AnalyzerEnrichmentTest :
    FunSpec({

        val metadataReader = AbsMetadataReader(contractJson)
        val embeddedParser =
            EmbeddedMetadataParser(
                detector = AudioFormatDetector(),
                parsers = listOf(Mp3Parser()),
            )

        test("embedded ID3v2 title enriches AnalyzedBook when no metadata.json") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/The Way of Kings"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Words of Radiance")
                                textFrame("TPE1", "Brandon Sanderson")
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", audioBytes)
                    val candidate = candidateForPath(rel, audioPath)

                    val book =
                        Analyzer(fixture.root, metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "Words of Radiance"
                    book.authors shouldBe listOf("Brandon Sanderson")
                    book.embeddedStatus shouldBe MetadataStatus.Available
                    book.embedded.shouldNotBeNull()
                    book.embedded?.format shouldBe AudioFormat.Mp3
                    book.embedded?.tags?.title shouldBe "Words of Radiance"
                    book.sources shouldContain MetadataSource.AUDIO_METATAGS
                }
            }
        }

        test("embedded artwork is the cover when no filesystem image exists") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Title"
                    val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Title")
                                apicFrame(mime = "image/jpeg", pictureType = 3, description = "Cover", imageBytes = fakeJpeg)
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", audioBytes)
                    val candidate = candidateForPath(rel, audioPath)

                    val book =
                        Analyzer(fixture.root, metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    val cover = book.cover.shouldBeInstanceOf<CoverSource.Embedded>()
                    cover.artwork.mime shouldBe "image/jpeg"
                    cover.artwork.bytes.toList() shouldBe fakeJpeg.toList()
                }
            }
        }

        test("filesystem cover.jpg trumps embedded artwork") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Title"
                    val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Title")
                                apicFrame(mime = "image/jpeg", pictureType = 3, description = "Cover", imageBytes = fakeJpeg)
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", audioBytes)
                    val coverFile = fixture.root.writeFile("$rel/cover.jpg", byteArrayOf(0x00))
                    val coverEntry = fileEntry("$rel/cover.jpg", FileType.IMAGE, size = 1)
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    coverEntry,
                                    fileEntry("$rel/01.mp3", FileType.AUDIO, size = Files.size(audioPath)),
                                ),
                        )

                    val book =
                        Analyzer(fixture.root, metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.cover shouldBe CoverSource.Filesystem(coverEntry)
                    // The embedded artwork is still preserved on `embedded` for clients that want it.
                    book.embedded?.artwork?.mime shouldBe "image/jpeg"
                    coverFile.toFile().exists() shouldBe true
                }
            }
        }

        test("ABS metadata.json title trumps embedded title") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Title"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TIT2", "Embedded Title") }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", audioBytes)
                    val metadataPath =
                        fixture.root.writeFile(
                            "$rel/metadata.json",
                            """{"title":"Sidecar Title"}""".toByteArray(),
                        )
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    fileEntry("$rel/01.mp3", FileType.AUDIO, size = Files.size(audioPath)),
                                    fileEntry("$rel/metadata.json", FileType.METADATA, size = Files.size(metadataPath)),
                                ),
                        )

                    val book =
                        Analyzer(fixture.root, metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "Sidecar Title"
                    book.embedded?.tags?.title shouldBe "Embedded Title"
                    book.sources shouldContain MetadataSource.ABS_METADATA
                    book.sources shouldContain MetadataSource.AUDIO_METATAGS
                }
            }
        }

        test("UnsupportedFormat does not drop the book; embeddedStatus carries the failure") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Title"
                    // Random bytes that aren't any known audio format.
                    val randomBytes = ByteArray(64) { it.toByte() }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", randomBytes)
                    val candidate = candidateForPath(rel, audioPath)

                    val book =
                        Analyzer(fixture.root, metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "Title" // folder-derived
                    book.embedded shouldBe null
                    val status = book.embeddedStatus.shouldBeInstanceOf<MetadataStatus.UnsupportedFormat>()
                    status.format shouldBe null // unrecognised magic bytes
                    book.sources shouldContain MetadataSource.FOLDER_STRUCTURE
                }
            }
        }
    })

private fun candidateForPath(
    rel: String,
    audioPath: Path,
): CandidateBook =
    CandidateBook(
        rootRelPath = rel,
        isFile = false,
        files =
            listOf(
                fileEntry(
                    "$rel/${audioPath.fileName}",
                    FileType.AUDIO,
                    size = Files.size(audioPath),
                ),
            ),
    )

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

private fun Path.writeAudioFile(
    relPath: String,
    bytes: ByteArray,
): Path = writeFile(relPath, bytes)

private fun Path.writeFile(
    relPath: String,
    bytes: ByteArray,
): Path {
    val target = resolve(relPath)
    Files.createDirectories(target.parent)
    Files.write(target, bytes)
    return target
}
