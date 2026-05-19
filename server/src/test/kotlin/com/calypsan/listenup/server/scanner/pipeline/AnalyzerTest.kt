package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.CoverSource
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.MetadataSource
import com.calypsan.listenup.api.dto.scanner.MetadataStatus
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackNumberSource
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.scanner.audioLibrary
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path

class AnalyzerTest :
    FunSpec({

        val metadataReader = AbsMetadataReader(contractJson)
        // Empty parser registry — synthetic FileEntry paths in these tests don't
        // resolve to real audio bytes, so every parse attempt yields IoError /
        // UnsupportedFormat. Tests asserting on embedded enrichment live in
        // AnalyzerEnrichmentTest with the real parser graph + on-disk fixtures.
        val embeddedParser = EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList())

        test("infers title, author, and series from folder shape alone") {
            audioLibrary {
                book("Sanderson/Stormlight/The Way of Kings") {
                    tracks(count = 2)
                }
            }.use { fixture ->
                runTest {
                    val analyzer = Analyzer(fixture.root, metadataReader, embeddedParser)
                    val candidate =
                        candidateOf(
                            "Sanderson/Stormlight/The Way of Kings",
                            files =
                                listOf(
                                    fileEntry("Sanderson/Stormlight/The Way of Kings/01 - Track.mp3", FileType.AUDIO),
                                    fileEntry("Sanderson/Stormlight/The Way of Kings/02 - Track.mp3", FileType.AUDIO),
                                ),
                        )
                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "The Way of Kings"
                    book.authors shouldContainExactly listOf("Sanderson")
                    book.series shouldContainExactly listOf(SeriesEntry("Stormlight"))
                    book.tracks.size shouldBe 2
                    book.sources shouldBe setOf(MetadataSource.FOLDER_STRUCTURE)
                }
            }
        }

        test("extracts ASIN, narrators, year, sequence from title-folder annotations") {
            audioLibrary {
                book("Sanderson/Stormlight/(2010) - Book 1 - The Way of Kings [B0015T963C] {Michael Kramer; Kate Reading}") {
                    tracks(count = 1)
                }
            }.use { fixture ->
                runTest {
                    val analyzer = Analyzer(fixture.root, metadataReader, embeddedParser)
                    val rel = "Sanderson/Stormlight/(2010) - Book 1 - The Way of Kings [B0015T963C] {Michael Kramer; Kate Reading}"
                    val candidate =
                        candidateOf(
                            rel,
                            files = listOf(fileEntry("$rel/01 - Track.mp3", FileType.AUDIO)),
                        )
                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "The Way of Kings"
                    book.asin shouldBe "B0015T963C"
                    book.narrators shouldContainExactly listOf("Michael Kramer", "Kate Reading")
                    book.publishedYear shouldBe 2010
                    book.series shouldContainExactly listOf(SeriesEntry("Stormlight", "1"))
                    book.sources shouldBe setOf(MetadataSource.FOLDER_STRUCTURE, MetadataSource.FILENAME)
                }
            }
        }

        test("metadata.json overrides folder-derived fields") {
            audioLibrary {
                book("Sanderson/Stormlight/Folder Title") {
                    tracks(count = 1)
                    metadataJson(
                        """
                        {
                          "title": "Override Title",
                          "subtitle": "From Sidecar",
                          "authors": ["Brandon Sanderson"],
                          "narrators": ["Michael Kramer"],
                          "series": ["Stormlight Archive #1"],
                          "publishedYear": 2010,
                          "asin": "B0015T963C",
                          "isbn": "9780765326355",
                          "description": "Epic fantasy.",
                          "publisher": "Tor Books",
                          "language": "English",
                          "genres": ["Fantasy"],
                          "tags": ["epic"],
                          "abridged": false,
                          "explicit": false
                        }
                        """.trimIndent(),
                    )
                }
            }.use { fixture ->
                runTest {
                    val analyzer = Analyzer(fixture.root, metadataReader, embeddedParser)
                    val rel = "Sanderson/Stormlight/Folder Title"
                    val candidate =
                        candidateOf(
                            rel,
                            files =
                                listOf(
                                    fileEntry("$rel/01 - Track.mp3", FileType.AUDIO),
                                    fileEntry("$rel/metadata.json", FileType.METADATA),
                                ),
                        )
                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "Override Title"
                    book.subtitle shouldBe "From Sidecar"
                    book.authors shouldContainExactly listOf("Brandon Sanderson")
                    book.narrators shouldContainExactly listOf("Michael Kramer")
                    book.series shouldContainExactly listOf(SeriesEntry("Stormlight Archive", "1"))
                    book.publishedYear shouldBe 2010
                    book.asin shouldBe "B0015T963C"
                    book.isbn shouldBe "9780765326355"
                    book.description shouldBe "Epic fantasy."
                    book.publisher shouldBe "Tor Books"
                    book.language shouldBe "English"
                    book.genres shouldContainExactly listOf("Fantasy")
                    book.tags shouldContainExactly listOf("epic")
                    book.abridged shouldBe false
                    book.explicit shouldBe false
                    book.sources shouldBe setOf(MetadataSource.FOLDER_STRUCTURE, MetadataSource.ABS_METADATA)
                }
            }
        }

        test("falls back to folder-derived values when metadata.json is malformed") {
            audioLibrary {
                book("Author/Title") {
                    tracks(count = 1)
                    metadataJson("{not valid json", name = "metadata.json")
                }
            }.use { fixture ->
                runTest {
                    val analyzer = Analyzer(fixture.root, metadataReader, embeddedParser)
                    val candidate =
                        candidateOf(
                            "Author/Title",
                            files =
                                listOf(
                                    fileEntry("Author/Title/01 - Track.mp3", FileType.AUDIO),
                                    fileEntry("Author/Title/metadata.json", FileType.METADATA),
                                ),
                        )
                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "Title"
                    book.authors shouldContainExactly listOf("Author")
                    book.sources shouldBe setOf(MetadataSource.FOLDER_STRUCTURE)
                }
            }
        }

        test("picks cover.<ext> over other images") {
            audioLibrary { /* paths only — no real files needed for this case */ }.use { fixture ->
                runTest {
                    val analyzer = Analyzer(fixture.root, metadataReader, embeddedParser)
                    val rel = "Author/Title"
                    val firstImage = fileEntry("$rel/folder.png", FileType.IMAGE)
                    val cover = fileEntry("$rel/cover.jpg", FileType.IMAGE)
                    val candidate =
                        candidateOf(
                            rel,
                            files =
                                listOf(
                                    firstImage,
                                    fileEntry("$rel/01 - Track.mp3", FileType.AUDIO),
                                    cover,
                                ),
                        )
                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.cover shouldBe CoverSource.Filesystem(cover)
                }
            }
        }

        test("falls back to first image when no cover.<ext> is present") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val analyzer = Analyzer(fixture.root, metadataReader, embeddedParser)
                    val rel = "Author/Title"
                    val firstImage = fileEntry("$rel/folder.png", FileType.IMAGE)
                    val candidate =
                        candidateOf(
                            rel,
                            files =
                                listOf(
                                    firstImage,
                                    fileEntry("$rel/01 - Track.mp3", FileType.AUDIO),
                                    fileEntry("$rel/back.jpg", FileType.IMAGE),
                                ),
                        )
                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.cover shouldBe CoverSource.Filesystem(firstImage)
                }
            }
        }

        test("emits null cover when there are no images") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val analyzer = Analyzer(fixture.root, metadataReader, embeddedParser)
                    val rel = "Author/Title"
                    val candidate =
                        candidateOf(
                            rel,
                            files = listOf(fileEntry("$rel/01 - Track.mp3", FileType.AUDIO)),
                        )
                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.cover.shouldBeNull()
                }
            }
        }

        test("sorts tracks by disc then track number") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val analyzer = Analyzer(fixture.root, metadataReader, embeddedParser)
                    val rel = "Author/Title"
                    val candidate =
                        candidateOf(
                            rel,
                            isFile = false,
                            discFolders = listOf("CD1", "CD2"),
                            files =
                                listOf(
                                    // Walker would have surfaced these in arbitrary order;
                                    // the Analyzer sorts them.
                                    fileEntry("$rel/CD2/02.mp3", FileType.AUDIO),
                                    fileEntry("$rel/CD1/02.mp3", FileType.AUDIO),
                                    fileEntry("$rel/CD2/01.mp3", FileType.AUDIO),
                                    fileEntry("$rel/CD1/01.mp3", FileType.AUDIO),
                                ),
                        )
                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.tracks.map { it.file.relPath } shouldContainExactly
                        listOf(
                            "$rel/CD1/01.mp3",
                            "$rel/CD1/02.mp3",
                            "$rel/CD2/01.mp3",
                            "$rel/CD2/02.mp3",
                        )
                    book.tracks.first().discNumber shouldBe 1
                    book.tracks.first().trackNumber shouldBe 1
                    book.tracks.first().discSource shouldBe TrackNumberSource.FOLDER
                }
            }
        }

        test("title falls back to titleFolder when ABS regex strips everything") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val analyzer = Analyzer(fixture.root, metadataReader, embeddedParser)
                    // Just an ASIN bracket, nothing else — parsed.title would be empty.
                    val rel = "Author/[B0015T963C]"
                    val candidate =
                        candidateOf(
                            rel,
                            files = listOf(fileEntry("$rel/01.mp3", FileType.AUDIO)),
                        )
                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    // Title should never be blank.
                    book.title.isNotBlank() shouldBe true
                    book.asin shouldBe "B0015T963C"
                }
            }
        }

        test("single-file book at library root is analyzed without crashing") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val analyzer = Analyzer(fixture.root, metadataReader, embeddedParser)
                    val candidate =
                        candidateOf(
                            "standalone.m4b",
                            isFile = true,
                            files = listOf(fileEntry("standalone.m4b", FileType.AUDIO)),
                        )
                    val result = analyzer.analyze(flowOf(candidate)).toList().single()

                    val book = result.getOrThrow()
                    book.title.isNotBlank() shouldBe true
                    book.tracks.size shouldBe 1
                }
            }
        }

        test("analyzer continues across multiple candidates") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val analyzer = Analyzer(fixture.root, metadataReader, embeddedParser)
                    val a =
                        candidateOf(
                            "Author/Book A",
                            files = listOf(fileEntry("Author/Book A/01.mp3", FileType.AUDIO)),
                        )
                    val b =
                        candidateOf(
                            "Author/Book B",
                            files = listOf(fileEntry("Author/Book B/01.mp3", FileType.AUDIO)),
                        )
                    val books = analyzer.analyze(flowOf(a, b)).toList().map { it.getOrThrow() }

                    books.map { it.title } shouldContainExactly listOf("Book A", "Book B")
                }
            }
        }

        test("a book whose primary audio fails to parse is still produced, with hasScanWarning") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val analyzer = Analyzer(fixture.root, metadataReader, embeddedParser)
                    val rel = "Author/Broken Book"
                    // Random non-audio bytes — no magic matches, the parser rejects this
                    // as an unsupported format. The book is still produced; the failure
                    // travels as embeddedStatus and raises hasScanWarning.
                    val malformedBytes = ByteArray(64) { it.toByte() }
                    val audioPath = writeBytes(fixture.root, "$rel/01.mp3", malformedBytes)
                    val candidate =
                        candidateOf(
                            rel,
                            files =
                                listOf(
                                    fileEntry(
                                        "$rel/01.mp3",
                                        FileType.AUDIO,
                                        size = Files.size(audioPath),
                                    ),
                                ),
                        )
                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.embeddedStatus.shouldNotBeNull()
                    book.hasScanWarning shouldBe true
                }
            }
        }

        test("a clean book has hasScanWarning false") {
            audioLibrary {}.use { fixture ->
                runTest {
                    // The real Mp3Parser so a well-formed file parses to
                    // MetadataStatus.Available — proving a successful parse
                    // does NOT raise the scan warning.
                    val realParser =
                        EmbeddedMetadataParser(
                            detector = AudioFormatDetector(),
                            parsers = listOf(Mp3Parser()),
                        )
                    val analyzer = Analyzer(fixture.root, metadataReader, realParser)
                    val rel = "Author/Clean Book"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TIT2", "Clean Book") }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = writeBytes(fixture.root, "$rel/01.mp3", audioBytes)
                    val candidate =
                        candidateOf(
                            rel,
                            files =
                                listOf(
                                    fileEntry(
                                        "$rel/01.mp3",
                                        FileType.AUDIO,
                                        size = Files.size(audioPath),
                                    ),
                                ),
                        )
                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.embeddedStatus shouldBe MetadataStatus.Available
                    book.hasScanWarning shouldBe false
                }
            }
        }

        test("read returns the live cover FileEntry, not a copy") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val analyzer = Analyzer(fixture.root, metadataReader, embeddedParser)
                    val rel = "Author/Title"
                    val cover = fileEntry("$rel/cover.jpg", FileType.IMAGE)
                    val candidate =
                        candidateOf(
                            rel,
                            files =
                                listOf(
                                    cover,
                                    fileEntry("$rel/01.mp3", FileType.AUDIO),
                                ),
                        )
                    val book =
                        analyzer
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.cover.shouldNotBeNull()
                    book.cover shouldBe CoverSource.Filesystem(cover)
                }
            }
        }
    })

private fun candidateOf(
    rootRelPath: String,
    files: List<FileEntry>,
    isFile: Boolean = false,
    discFolders: List<String> = emptyList(),
): CandidateBook =
    CandidateBook(
        rootRelPath = rootRelPath,
        isFile = isFile,
        files = files,
        discFolders = discFolders,
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

private fun writeBytes(
    root: Path,
    relPath: String,
    bytes: ByteArray,
): Path {
    val target = root.resolve(relPath)
    Files.createDirectories(target.parent)
    Files.write(target, bytes)
    return target
}
