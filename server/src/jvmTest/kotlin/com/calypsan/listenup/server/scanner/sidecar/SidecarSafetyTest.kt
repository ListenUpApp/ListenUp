package com.calypsan.listenup.server.scanner.sidecar

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.io.SIDECAR_MAX_BYTES
import com.calypsan.listenup.server.scanner.audioLibrary
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.pipeline.Analyzer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path as NioPath
import kotlinx.io.files.Path as IoPath

/**
 * Two properties of opening a sidecar, both of which matter because sidecars are operator-supplied
 * files the operator did not author.
 *
 * **Where it points.** The Walker deliberately emits a symlink as a leaf and never recurses through
 * it, which keeps enumeration cycle-safe — but the leaf is still opened later, and opening follows
 * the link wherever it goes, including out of the library the operator pointed us at. The refusal
 * therefore belongs at the open, not at the enumeration.
 *
 * **How big it is.** A sidecar is metadata — a title, a description, a contributor list. Reading
 * one whole into memory is fine at that scale and is not fine at media scale, and nothing about a
 * filename guarantees which one is on disk.
 */
class SidecarSafetyTest :
    FunSpec({

        val metadataReader = AbsMetadataReader(contractJson)
        val embeddedParser =
            EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = listOf(Mp3Parser()))

        test("a symlinked sidecar is not read") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/The Book"
                    val bookDir = fixture.root.resolve(rel)
                    Files.createDirectories(bookDir)
                    Files.write(bookDir.resolve("01.mp3"), oneSecondMp3())

                    // The link's target sits outside the library root entirely.
                    val elsewhere = Files.createTempDirectory("listenup-outside-")
                    val target = elsewhere.resolve("notes.txt")
                    Files.write(target, "Text from outside the library root.".toByteArray())
                    Files.createSymbolicLink(bookDir.resolve("desc.txt"), target)

                    val book = analyzeWithDescTxt(fixture.root, rel, metadataReader, embeddedParser)

                    book.description.shouldBeNull()
                }
            }
        }

        test("a real sidecar beside the audio is still read") {
            // Regression guard: the refusal must turn away links, not sidecars.
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/The Book"
                    val bookDir = fixture.root.resolve(rel)
                    Files.createDirectories(bookDir)
                    Files.write(bookDir.resolve("01.mp3"), oneSecondMp3())
                    Files.write(bookDir.resolve("desc.txt"), "A real description.".toByteArray())

                    val book = analyzeWithDescTxt(fixture.root, rel, metadataReader, embeddedParser)

                    book.description shouldBe "A real description."
                }
            }
        }

        test("a sidecar larger than the read cap is refused") {
            runTest {
                val dir = Files.createTempDirectory("listenup-sidecar-over-")
                val file = dir.resolve("desc.txt")
                Files.write(file, ByteArray((SIDECAR_MAX_BYTES + 1).toInt()) { 'a'.code.toByte() })

                DescTxtParser().parse(IoPath(file.toString())).shouldBeNull()
            }
        }

        test("a sidecar exactly at the read cap still parses") {
            runTest {
                val dir = Files.createTempDirectory("listenup-sidecar-at-")
                val file = dir.resolve("desc.txt")
                Files.write(file, ByteArray(SIDECAR_MAX_BYTES.toInt()) { 'a'.code.toByte() })

                val parsed = DescTxtParser().parse(IoPath(file.toString()))

                parsed?.description?.length shouldBe SIDECAR_MAX_BYTES.toInt()
            }
        }
    })

/** A minimal but genuinely parseable one-second MP3, so the candidate yields a playable track. */
private fun oneSecondMp3(): ByteArray =
    buildMp3File {
        id3v2(version = 4) {}
        mpegFrames(durationSeconds = 1)
    }

/** Run the real [Analyzer] over `<rel>/01.mp3` + `<rel>/desc.txt` with the real [DescTxtParser]. */
private suspend fun analyzeWithDescTxt(
    libraryRoot: NioPath,
    rel: String,
    metadataReader: AbsMetadataReader,
    embeddedParser: EmbeddedMetadataParser,
): AnalyzedBook {
    val candidate =
        CandidateBook(
            rootRelPath = rel,
            isFile = false,
            files =
                listOf(
                    fileEntry("$rel/01.mp3", FileType.AUDIO),
                    fileEntry("$rel/desc.txt", FileType.TEXT),
                ),
        )
    return Analyzer(
        IoPath(libraryRoot.toString()),
        metadataReader,
        embeddedParser,
        sidecarParsers = listOf(DescTxtParser()),
    ).analyze(flowOf(candidate))
        .toList()
        .single()
        .getOrThrow()
}

private fun fileEntry(
    relPath: String,
    fileType: FileType,
): FileEntry =
    FileEntry(
        relPath = relPath,
        name = relPath.substringAfterLast('/'),
        ext = relPath.substringAfterLast('.', "").lowercase(),
        size = 0,
        mtimeMs = 0,
        inode = null,
        fileType = fileType,
    )
