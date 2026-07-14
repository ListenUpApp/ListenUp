package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.BookChapterSource
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.CoverSource
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.dto.scanner.MetadataStatus
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.embeddedmeta.fixtures.buildMp3File
import com.calypsan.listenup.server.embeddedmeta.format.mp3.Mp3Parser
import com.calypsan.listenup.server.scanner.audioLibrary
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path as NioPath
import kotlinx.io.files.Path

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
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
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
                    book.fieldProvenance[BookField.TITLE]?.kind shouldBe FieldSourceKind.EMBEDDED
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
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
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
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
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
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "Sidecar Title"
                    book.embedded?.tags?.title shouldBe "Embedded Title"
                    // Winner-based provenance: metadata.json wins the title over the embedded tag (the
                    // embedded value is still preserved on `book.embedded` as raw signal, asserted above).
                    book.fieldProvenance[BookField.TITLE]?.kind shouldBe FieldSourceKind.ABS_METADATA
                }
            }
        }

        test("non-default precedence makes embedded title win over metadata.json") {
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

                    // Embedded ahead of metadata.json — embedded wins despite both being present.
                    val book =
                        Analyzer(
                            Path(fixture.root.toString()),
                            metadataReader,
                            embeddedParser,
                            precedence = MetadataPrecedence.parse("embedded,metadata.json"),
                        ).analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "Embedded Title"
                }
            }
        }

        test("precedence omitting embedded ignores embedded tags for the field") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Folder Title"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TIT2", "Embedded Title") }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", audioBytes)
                    val candidate = candidateForPath(rel, audioPath)

                    // Embedded omitted entirely — title falls through to the folder name.
                    val book =
                        Analyzer(
                            Path(fixture.root.toString()),
                            metadataReader,
                            embeddedParser,
                            precedence = MetadataPrecedence.parse("metadata.json,sidecar,filename,folder"),
                        ).analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "Folder Title"
                    // Embedded tags are still parsed and preserved verbatim — only the
                    // resolved view skips them.
                    book.embedded?.tags?.title shouldBe "Embedded Title"
                }
            }
        }

        test("metadata.json chapters override embedded CHAP frames") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Title"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Title")
                                chapFrame("emb1", startMs = 0, endMs = 10_000, title = "Embedded Chapter A")
                                chapFrame("emb2", startMs = 10_000, endMs = 20_000, title = "Embedded Chapter B")
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", audioBytes)
                    val metadataJson =
                        """
                        {
                            "title": "Title",
                            "chapters": [
                                {"id": 0, "start": 0.0, "end": 30.5, "title": "Sidecar Chapter 1"},
                                {"id": 1, "start": 30.5, "end": 60.0, "title": "Sidecar Chapter 2"},
                                {"id": 2, "start": 60.0, "end": 90.0, "title": "Sidecar Chapter 3"}
                            ]
                        }
                        """.trimIndent()
                    val metadataPath = fixture.root.writeFile("$rel/metadata.json", metadataJson.toByteArray())
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
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.chapters shouldHaveSize 3
                    book.chapters[0].index shouldBe 1
                    book.chapters[0].title shouldBe "Sidecar Chapter 1"
                    book.chapters[0].startMs shouldBe 0L
                    book.chapters[0].endMs shouldBe 30_500L
                    book.chapters[1].index shouldBe 2
                    book.chapters[2].index shouldBe 3
                    book.chaptersSource shouldBe BookChapterSource.AbsMetadata
                    // embedded chapters preserved verbatim on `embedded`
                    book.embedded?.chapters?.shouldHaveSize(2)
                    book.embedded
                        ?.chapters
                        ?.get(0)
                        ?.title shouldBe "Embedded Chapter A"
                }
            }
        }

        test("embedded chapters surface when metadata.json carries no chapters") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Title"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Title")
                                chapFrame("emb1", startMs = 0, endMs = 10_000, title = "Embedded Chapter A")
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", audioBytes)
                    val candidate = candidateForPath(rel, audioPath)

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.chapters shouldHaveSize 1
                    book.chapters[0].title shouldBe "Embedded Chapter A"
                    val source = book.chaptersSource.shouldBeInstanceOf<BookChapterSource.Embedded>()
                    source.parserSource shouldBe ChapterSource.Id3v2Chap
                }
            }
        }

        test("no chapter signal anywhere → empty list and BookChapterSource.None") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Title"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TIT2", "Title") }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", audioBytes)
                    val candidate = candidateForPath(rel, audioPath)

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.chapters shouldBe emptyList()
                    book.chaptersSource shouldBe BookChapterSource.None
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
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.title shouldBe "Title" // folder-derived
                    book.embedded shouldBe null
                    val status = book.embeddedStatus.shouldBeInstanceOf<MetadataStatus.UnsupportedFormat>()
                    status.format shouldBe null // unrecognised magic bytes
                    // The folder-leaf title resolves via the title parser (FILENAME tier).
                    book.fieldProvenance[BookField.TITLE]?.kind shouldBe FieldSourceKind.FILENAME
                }
            }
        }

        test("multi-file MP3 book with no chapter sources synthesizes one chapter per track") {
            audioLibrary {}.use { fixture ->
                runTest {
                    // Book title comes from the folder name ("Multi") because
                    // the tracks carry no TIT2 — no collision between book title
                    // and chapter titles, so filename-based resolution fires.
                    val rel = "Author/Multi"
                    val track1Bytes =
                        buildMp3File {
                            id3v2(version = 4) {}
                            mpegFrames(durationSeconds = 1)
                        }
                    val track2Bytes =
                        buildMp3File {
                            id3v2(version = 4) {}
                            mpegFrames(durationSeconds = 1)
                        }
                    val track1Path = fixture.root.writeAudioFile("$rel/01 Foreword.mp3", track1Bytes)
                    val track2Path = fixture.root.writeAudioFile("$rel/02 Prologue.mp3", track2Bytes)
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    fileEntry("$rel/01 Foreword.mp3", FileType.AUDIO, size = Files.size(track1Path)),
                                    fileEntry("$rel/02 Prologue.mp3", FileType.AUDIO, size = Files.size(track2Path)),
                                ),
                        )

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.chapters shouldHaveSize 2
                    book.chapters[0].index shouldBe 1
                    book.chapters[0].title shouldBe "Foreword"
                    book.chapters[0].startMs shouldBe 0L
                    book.chapters[1].index shouldBe 2
                    book.chapters[1].title shouldBe "Prologue"
                    book.chapters[1].startMs shouldBe book.chapters[0].endMs
                    book.chaptersSource shouldBe BookChapterSource.SynthesizedFromTracks
                }
            }
        }

        test("metadata.json chapters override synthesized chapters on multi-file book") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Multi"
                    val track1Bytes =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TIT2", "Foreword") }
                            mpegFrames(durationSeconds = 1)
                        }
                    val track2Bytes =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TIT2", "Prologue") }
                            mpegFrames(durationSeconds = 1)
                        }
                    val t1 = fixture.root.writeAudioFile("$rel/01.mp3", track1Bytes)
                    val t2 = fixture.root.writeAudioFile("$rel/02.mp3", track2Bytes)
                    val metadataJson =
                        """
                        {
                            "title": "Multi",
                            "chapters": [
                                {"id": 0, "start": 0.0, "end": 30.0, "title": "Sidecar 1"},
                                {"id": 1, "start": 30.0, "end": 60.0, "title": "Sidecar 2"}
                            ]
                        }
                        """.trimIndent()
                    val metaPath = fixture.root.writeFile("$rel/metadata.json", metadataJson.toByteArray())
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    fileEntry("$rel/01.mp3", FileType.AUDIO, size = Files.size(t1)),
                                    fileEntry("$rel/02.mp3", FileType.AUDIO, size = Files.size(t2)),
                                    fileEntry("$rel/metadata.json", FileType.METADATA, size = Files.size(metaPath)),
                                ),
                        )

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.chapters shouldHaveSize 2
                    book.chapters[0].title shouldBe "Sidecar 1"
                    book.chaptersSource shouldBe BookChapterSource.AbsMetadata
                }
            }
        }

        test("embedded CHAP frames on primary track override synthesized chapters") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Multi"
                    val primaryBytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Multi")
                                chapFrame("c1", startMs = 0, endMs = 5_000, title = "Embedded A")
                                chapFrame("c2", startMs = 5_000, endMs = 10_000, title = "Embedded B")
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val secondaryBytes =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TIT2", "Track Two") }
                            mpegFrames(durationSeconds = 1)
                        }
                    val t1 = fixture.root.writeAudioFile("$rel/01.mp3", primaryBytes)
                    val t2 = fixture.root.writeAudioFile("$rel/02.mp3", secondaryBytes)
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    fileEntry("$rel/01.mp3", FileType.AUDIO, size = Files.size(t1)),
                                    fileEntry("$rel/02.mp3", FileType.AUDIO, size = Files.size(t2)),
                                ),
                        )

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.chapters shouldHaveSize 2
                    book.chapters[0].title shouldBe "Embedded A"
                    val source = book.chaptersSource.shouldBeInstanceOf<BookChapterSource.Embedded>()
                    source.parserSource shouldBe ChapterSource.Id3v2Chap
                }
            }
        }

        test("embedded comment is the description fallback when no description tag") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/From the Comment"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Book")
                                commFrame(text = "From the comment.")
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", audioBytes)
                    val candidate = candidateForPath(rel, audioPath)

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.description shouldBe "From the comment."
                }
            }
        }

        test("explicit description still wins over comment") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Desc Wins"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) {
                                textFrame("TIT2", "Book")
                                txxxFrame("description", "Real desc.")
                                commFrame(text = "Comment.")
                            }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", audioBytes)
                    val candidate = candidateForPath(rel, audioPath)

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.description shouldBe "Real desc."
                }
            }
        }

        test("single-file book never triggers synthesis") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Single"
                    val audioBytes =
                        buildMp3File {
                            id3v2(version = 4) { textFrame("TIT2", "Single") }
                            mpegFrames(durationSeconds = 1)
                        }
                    val audioPath = fixture.root.writeAudioFile("$rel/01.mp3", audioBytes)
                    val candidate = candidateForPath(rel, audioPath)

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    // Single-file with no chapter sources → None, not SynthesizedFromTracks.
                    book.chapters shouldBe emptyList()
                    book.chaptersSource shouldBe BookChapterSource.None
                }
            }
        }

        test("multi-file: one track's parse failure produces zero-length chapter, book still emerges") {
            audioLibrary {}.use { fixture ->
                runTest {
                    val rel = "Author/Multi"
                    val track1Bytes =
                        buildMp3File {
                            id3v2(version = 4) {}
                            mpegFrames(durationSeconds = 1)
                        }
                    // Random non-audio bytes for track 2 — parser should reject as UnsupportedFormat.
                    val track2Bytes = ByteArray(64) { it.toByte() }
                    // Meaningful filenames so cleanFilename() can derive chapter titles.
                    val t1 = fixture.root.writeAudioFile("$rel/01 Real Track.mp3", track1Bytes)
                    val t2 = fixture.root.writeAudioFile("$rel/02 Bad Track.mp3", track2Bytes)
                    val candidate =
                        CandidateBook(
                            rootRelPath = rel,
                            isFile = false,
                            files =
                                listOf(
                                    fileEntry("$rel/01 Real Track.mp3", FileType.AUDIO, size = Files.size(t1)),
                                    fileEntry("$rel/02 Bad Track.mp3", FileType.AUDIO, size = Files.size(t2)),
                                ),
                        )

                    val book =
                        Analyzer(Path(fixture.root.toString()), metadataReader, embeddedParser)
                            .analyze(flowOf(candidate))
                            .toList()
                            .single()
                            .getOrThrow()

                    book.chapters shouldHaveSize 2
                    book.chapters[0].title shouldBe "Real Track"
                    // Track 2's chapter exists but is zero-length: failed parse → 0ms duration.
                    book.chapters[1].startMs shouldBe book.chapters[0].endMs
                    book.chapters[1].endMs shouldBe book.chapters[1].startMs
                    book.chaptersSource shouldBe BookChapterSource.SynthesizedFromTracks
                }
            }
        }
    })

private fun candidateForPath(
    rel: String,
    audioPath: NioPath,
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

private fun NioPath.writeAudioFile(
    relPath: String,
    bytes: ByteArray,
): NioPath = writeFile(relPath, bytes)

private fun NioPath.writeFile(
    relPath: String,
    bytes: ByteArray,
): NioPath {
    val target = resolve(relPath)
    Files.createDirectories(target.parent)
    Files.write(target, bytes)
    return target
}
