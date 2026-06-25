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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path as NioPath
import java.security.MessageDigest
import kotlinx.io.files.Path

/**
 * EBOOK collection wired through the REAL [Analyzer]: a book folder containing an audio
 * track + a `.pdf` yields an [com.calypsan.listenup.api.dto.scanner.AnalyzedBook] whose
 * `documents` carry the pdf with a BOOK-ROOT-relative path.
 *
 * This proves the seam `DocumentCollectorTest` cannot: that the Analyzer passes the correct
 * roots — the library root and the book root (`rootPath.resolve(candidate.rootRelPath)`) —
 * to [com.calypsan.listenup.server.scanner.document.DocumentCollector]. `FileEntry.relPath`
 * is library-root-relative; a swapped root would yield the wrong `relPath` and this test
 * would catch it.
 */
class AnalyzerDocumentCollectionTest :
    FunSpec({

        val metadataReader = AbsMetadataReader(contractJson)
        val embeddedParser =
            EmbeddedMetadataParser(
                detector = AudioFormatDetector(),
                parsers = listOf(Mp3Parser()),
            )

        test("a pdf in a subfolder beside the audio is collected with a book-root-relative path") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/The Book"
                    val mp3 =
                        buildMp3File {
                            id3v2(version = 4) {}
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeFile("$rel/01.mp3", mp3)
                    val pdfBytes = "hello pdf".toByteArray()
                    val pdfPath = fixture.root.writeFile("$rel/extras/map.pdf", pdfBytes)

                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    fileEntry("$rel/01.mp3", Files.size(audioPath), FileType.AUDIO),
                                    fileEntry("$rel/extras/map.pdf", Files.size(pdfPath), FileType.EBOOK),
                                ),
                        )

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.documents.size shouldBe 1
                    val doc = book.documents.single()
                    // Book-root-relative (NOT library-root-relative): "extras/map.pdf", not "Author/The Book/extras/map.pdf".
                    doc.relPath.replace('\\', '/') shouldBe "extras/map.pdf"
                    doc.format shouldBe "pdf"
                    doc.size shouldBe pdfBytes.size.toLong()
                    doc.hash shouldBe sha256Hex(pdfBytes)
                }
            }
        }

        test("a book with only audio has empty documents") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Audio Only"
                    val mp3 =
                        buildMp3File {
                            id3v2(version = 4) {}
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeFile("$rel/01.mp3", mp3)
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files = listOf(fileEntry("$rel/01.mp3", Files.size(audioPath), FileType.AUDIO)),
                        )

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.documents.shouldBeEmpty()
                }
            }
        }
    })

private fun fileEntry(
    relPath: String,
    size: Long,
    fileType: FileType,
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

private fun NioPath.writeFile(
    relPath: String,
    bytes: ByteArray,
): NioPath {
    val target = resolve(relPath)
    Files.createDirectories(target.parent)
    Files.write(target, bytes)
    return target
}

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
