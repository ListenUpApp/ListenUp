package com.calypsan.listenup.server.scanner.sidecar

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.scanner.audioLibrary
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.pipeline.Analyzer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.io.files.Path as IoPath

/**
 * Exercises the sidecar precedence tier through the real [Analyzer] path with
 * a fake [SidecarParser]. There is no single `mergeWithPrecedence` method
 * — the Analyzer composes via per-field `pick*` chains, so these tests assert
 * the resolved [com.calypsan.listenup.api.dto.scanner.AnalyzedBook] fields.
 *
 * Sidecar precedence: `metadata.json > embedded > sidecar > filename > folder`.
 *
 * The fake parser matches a `book.nfo` file and returns a fixed
 * [SidecarMetadata]; it never touches disk, so the precedence logic is the
 * only thing under test. Embedded-tier tests use the real MP3 parser graph
 * against on-disk fixture bytes, mirroring `AnalyzerEnrichmentTest`.
 */
class SidecarMergeTest :
    FunSpec({

        val metadataReader = AbsMetadataReader(contractJson)
        val embeddedParserEmpty =
            EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList())
        val embeddedParserReal =
            EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = listOf(Mp3Parser()))

        test("sidecar description is used when no metadata.json and no embedded description") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Title"
                    val analyzer =
                        Analyzer(
                            IoPath(fixture.root.toString()),
                            metadataReader,
                            embeddedParserEmpty,
                            sidecarParsers = listOf(FakeNfoParser(SidecarMetadata(description = "from-sidecar"))),
                        )
                    val candidate =
                        candidateOf(
                            rel,
                            files =
                                listOf(
                                    fileEntry("$rel/01.mp3", FileType.AUDIO),
                                    fileEntry("$rel/book.nfo", FileType.METADATA),
                                ),
                        )

                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.description shouldBe "from-sidecar"
                    book.fieldProvenance[BookField.DESCRIPTION]?.kind shouldBe FieldSourceKind.SIDECAR
                }
            }
        }

        test("sidecar description loses to metadata.json") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Title"
                    fixture.root.writeFile("$rel/metadata.json", """{"title":"Title","description":"from-abs"}""")
                    val analyzer =
                        Analyzer(
                            IoPath(fixture.root.toString()),
                            metadataReader,
                            embeddedParserEmpty,
                            sidecarParsers = listOf(FakeNfoParser(SidecarMetadata(description = "from-sidecar"))),
                        )
                    val candidate =
                        candidateOf(
                            rel,
                            files =
                                listOf(
                                    fileEntry("$rel/01.mp3", FileType.AUDIO),
                                    fileEntry("$rel/metadata.json", FileType.METADATA),
                                    fileEntry("$rel/book.nfo", FileType.METADATA),
                                ),
                        )

                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.description shouldBe "from-abs"
                }
            }
        }

        test("sidecar description loses to embedded") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Title"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Title")
                                txxxFrame(description = "description", value = "from-embedded")
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", audioBytes)
                    val analyzer =
                        Analyzer(
                            IoPath(fixture.root.toString()),
                            metadataReader,
                            embeddedParserReal,
                            sidecarParsers = listOf(FakeNfoParser(SidecarMetadata(description = "from-sidecar"))),
                        )
                    val candidate =
                        candidateOf(
                            rel,
                            files =
                                listOf(
                                    fileEntry("$rel/01.mp3", FileType.AUDIO, size = Files.size(audioPath)),
                                    fileEntry("$rel/book.nfo", FileType.METADATA),
                                ),
                        )

                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.description shouldBe "from-embedded"
                }
            }
        }

        test("sidecar publishYear slots between embedded and filename") {
            audioLibrary {}.use { fixture ->
                runTest {
                    // Filename carries a (2005) year prefix; sidecar carries 2018.
                    // Sidecar wins over filename.
                    val rel = "Author/(2005) - Title"
                    val analyzer =
                        Analyzer(
                            IoPath(fixture.root.toString()),
                            metadataReader,
                            embeddedParserEmpty,
                            sidecarParsers = listOf(FakeNfoParser(SidecarMetadata(publishYear = 2018))),
                        )
                    val candidate =
                        candidateOf(
                            rel,
                            files =
                                listOf(
                                    fileEntry("$rel/01.mp3", FileType.AUDIO),
                                    fileEntry("$rel/book.nfo", FileType.METADATA),
                                ),
                        )

                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.publishedYear shouldBe 2018
                }
            }
        }

        test("sidecar publishYear loses to embedded year") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Title"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Title")
                                textFrame("TYER", "1999")
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", audioBytes)
                    val analyzer =
                        Analyzer(
                            IoPath(fixture.root.toString()),
                            metadataReader,
                            embeddedParserReal,
                            sidecarParsers = listOf(FakeNfoParser(SidecarMetadata(publishYear = 2018))),
                        )
                    val candidate =
                        candidateOf(
                            rel,
                            files =
                                listOf(
                                    fileEntry("$rel/01.mp3", FileType.AUDIO, size = Files.size(audioPath)),
                                    fileEntry("$rel/book.nfo", FileType.METADATA),
                                ),
                        )

                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.publishedYear shouldBe 1999
                }
            }
        }

        test("sidecar contributors map to authors and narrators by role") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "FolderAuthor/Title"
                    val sidecar =
                        SidecarMetadata(
                            contributors =
                                listOf(
                                    SidecarContributor("Brandon Sanderson", "author"),
                                    SidecarContributor("Michael Kramer", "narrator"),
                                    SidecarContributor("Kate Reading", "narrator"),
                                ),
                        )
                    val analyzer =
                        Analyzer(
                            IoPath(fixture.root.toString()),
                            metadataReader,
                            embeddedParserEmpty,
                            sidecarParsers = listOf(FakeNfoParser(sidecar)),
                        )
                    val candidate =
                        candidateOf(
                            rel,
                            files =
                                listOf(
                                    fileEntry("$rel/01.mp3", FileType.AUDIO),
                                    fileEntry("$rel/book.nfo", FileType.METADATA),
                                ),
                        )

                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    // Sidecar authors win over the "FolderAuthor" folder tier.
                    book.authors shouldContainExactly listOf("Brandon Sanderson")
                    book.narrators shouldContainExactly listOf("Michael Kramer", "Kate Reading")
                }
            }
        }

        test("parseSidecars returns null when no sidecar files present") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Title"
                    val analyzer =
                        Analyzer(
                            IoPath(fixture.root.toString()),
                            metadataReader,
                            embeddedParserEmpty,
                            sidecarParsers = listOf(FakeNfoParser(SidecarMetadata(description = "from-sidecar"))),
                        )
                    // No .nfo file — nothing matches the fake parser.
                    val candidate =
                        candidateOf(rel, files = listOf(fileEntry("$rel/01.mp3", FileType.AUDIO)))

                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.description shouldBe null
                    book.fieldProvenance.values.map { it.kind } shouldNotContain FieldSourceKind.SIDECAR
                }
            }
        }

        test("a SidecarParser that throws does not abort the scan") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Title"
                    val analyzer =
                        Analyzer(
                            IoPath(fixture.root.toString()),
                            metadataReader,
                            embeddedParserEmpty,
                            sidecarParsers = listOf(ThrowingNfoParser()),
                        )
                    val candidate =
                        candidateOf(
                            rel,
                            files =
                                listOf(
                                    fileEntry("$rel/01.mp3", FileType.AUDIO),
                                    fileEntry("$rel/book.nfo", FileType.METADATA),
                                ),
                        )

                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    // The throwing parser is treated as null: folder-derived title survives.
                    book.title shouldBe "Title"
                    book.description shouldBe null
                    book.fieldProvenance.values.map { it.kind } shouldNotContain FieldSourceKind.SIDECAR
                }
            }
        }
    })

/** Returns a fixed [SidecarMetadata] for any `.nfo` file; never touches disk. */
private class FakeNfoParser(
    private val result: SidecarMetadata,
) : SidecarParser {
    override val supportedFilenames: Set<String> = emptySet()
    override val supportedExtensions: Set<String> = setOf("nfo")

    override suspend fun parse(file: IoPath): SidecarMetadata = result
}

/** Always throws — verifies the Analyzer defends against parser defects. */
private class ThrowingNfoParser : SidecarParser {
    override val supportedFilenames: Set<String> = emptySet()
    override val supportedExtensions: Set<String> = setOf("nfo")

    override suspend fun parse(file: IoPath): SidecarMetadata = error("parser defect")
}

private fun candidateOf(
    rootRelPath: String,
    files: List<FileEntry>,
): CandidateBook =
    CandidateBook(
        rootRelPath = rootRelPath,
        isFile = false,
        files = files,
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
    contents: String,
): Path = writeFile(relPath, contents.toByteArray())

private fun Path.writeFile(
    relPath: String,
    bytes: ByteArray,
): Path {
    val target = resolve(relPath)
    Files.createDirectories(target.parent)
    Files.write(target, bytes)
    return target
}
