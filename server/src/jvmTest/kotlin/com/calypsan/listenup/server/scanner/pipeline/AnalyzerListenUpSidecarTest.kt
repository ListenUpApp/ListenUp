package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.SidecarCurationChapter
import com.calypsan.listenup.api.sync.UserEditedField
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.scanner.audioLibrary
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.sidecar.ListenUpSidecarReader
import com.calypsan.listenup.server.sidecar.ListenUpSidecar
import com.calypsan.listenup.server.sidecar.SidecarChapter
import com.calypsan.listenup.server.sidecar.SidecarChapters
import com.calypsan.listenup.server.sidecar.SidecarCuratedMetadata
import com.calypsan.listenup.server.sidecar.SidecarIdentity
import com.calypsan.listenup.server.sidecar.SidecarJson
import com.calypsan.listenup.server.sidecar.SidecarWriteStateRepository
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

/**
 * Analyzer integration for the `listenup.json` top-precedence slot: an External sidecar's
 * fields beat every other source (including `metadata.json`), a SelfWritten sidecar leaves
 * the compose result identical to no-sidecar, and External curation (provenance + USER
 * chapters) rides out on [AnalyzedBook.sidecarCuration].
 */
class AnalyzerListenUpSidecarTest :
    FunSpec({

        val metadataReader = AbsMetadataReader(contractJson)
        val emptyParser = EmbeddedMetadataParser(detector = AudioFormatDetector(), parsers = emptyList())

        fun curatedSidecar(
            title: String,
            userEditedFields: List<String> = listOf("TITLE"),
            chapters: SidecarChapters? = null,
        ): ListenUpSidecar =
            ListenUpSidecar(
                identity = SidecarIdentity(titleAuthor = "$title / Author"),
                metadata = SidecarCuratedMetadata(title = title, tags = listOf("restored-tag")),
                userEditedFields = userEditedFields,
                chapters = chapters,
            )

        test("an External listenup.json title beats a conflicting metadata.json title") {
            withSqlDatabase {
                audioLibrary {
                    book("Author/My Book") {
                        audio("01.mp3")
                        metadataJson("""{"title":"ABS Title"}""")
                    }
                }.use { fixture ->
                    runTest {
                        val bookDir = fixture.root / "Author/My Book"
                        (bookDir / "listenup.json").writeBytes(SidecarJson.serialize(curatedSidecar("Curated Title")))
                        val reader = ListenUpSidecarReader(SidecarWriteStateRepository(sql))

                        val book = analyze(fixture.root.toString(), "Author/My Book", metadataReader, emptyParser, reader)

                        book.title shouldBe "Curated Title"
                        book.tags shouldContainExactly listOf("restored-tag")
                        val curation = book.sidecarCuration
                        curation.shouldNotBeNull()
                        curation.userEditedFields shouldBe setOf(UserEditedField.TITLE)
                        curation.userChapters.shouldBeNull()
                    }
                }
            }
        }

        test("a SelfWritten listenup.json leaves the result identical to no-sidecar") {
            withSqlDatabase {
                audioLibrary {
                    book("Author/My Book") {
                        audio("01.mp3")
                        metadataJson("""{"title":"ABS Title"}""")
                    }
                }.use { fixture ->
                    runTest {
                        sql.seedTestLibraryAndFolder()
                        sql.seedTestBook(bookId = "book1")
                        val bookDir = fixture.root / "Author/My Book"
                        val bytes = SidecarJson.serialize(curatedSidecar("Curated Title"))
                        (bookDir / "listenup.json").writeBytes(bytes)
                        val writeState = SidecarWriteStateRepository(sql)
                        writeState.save("book1", hashBytesSha256(bytes), writtenAtMs = 1L)
                        val reader = ListenUpSidecarReader(writeState)

                        val book = analyze(fixture.root.toString(), "Author/My Book", metadataReader, emptyParser, reader)

                        book.title shouldBe "ABS Title"
                        book.sidecarCuration.shouldBeNull()
                    }
                }
            }
        }

        test("External USER chapters ride out on sidecarCuration.userChapters") {
            withSqlDatabase {
                audioLibrary {
                    book("Author/My Book") {
                        audio("01.mp3")
                    }
                }.use { fixture ->
                    runTest {
                        val bookDir = fixture.root / "Author/My Book"
                        val sidecar =
                            curatedSidecar(
                                title = "Curated Title",
                                chapters =
                                    SidecarChapters(
                                        source = "USER",
                                        entries =
                                            listOf(
                                                SidecarChapter(title = "Prelude", startMs = 0L),
                                                SidecarChapter(title = "Chapter One", startMs = 90_000L),
                                            ),
                                    ),
                            )
                        (bookDir / "listenup.json").writeBytes(SidecarJson.serialize(sidecar))
                        val reader = ListenUpSidecarReader(SidecarWriteStateRepository(sql))

                        val book = analyze(fixture.root.toString(), "Author/My Book", metadataReader, emptyParser, reader)

                        val curation = book.sidecarCuration
                        curation.shouldNotBeNull()
                        curation.userChapters shouldBe
                            listOf(
                                SidecarCurationChapter(title = "Prelude", startMs = 0L),
                                SidecarCurationChapter(title = "Chapter One", startMs = 90_000L),
                            )
                    }
                }
            }
        }
    })

private suspend fun analyze(
    root: String,
    rel: String,
    metadataReader: AbsMetadataReader,
    parser: EmbeddedMetadataParser,
    reader: ListenUpSidecarReader,
): AnalyzedBook =
    Analyzer(
        rootPath = Path(root),
        metadataReader = metadataReader,
        embeddedMetadataParser = parser,
        listenUpSidecarReader = reader,
    ).analyze(flowOf(candidateFor(root, rel)))
        .toList()
        .single()
        .getOrThrow()

private fun candidateFor(
    root: String,
    rel: String,
): CandidateBook {
    val bookDir =
        java.nio.file.Path
            .of(root, rel)
    val files =
        Files
            .list(bookDir)
            .use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
            .map { name ->
                val ext = name.substringAfterLast('.', "").lowercase()
                FileEntry(
                    relPath = "$rel/$name",
                    name = name,
                    ext = ext,
                    size = Files.size(bookDir.resolve(name)),
                    mtimeMs = 0L,
                    fileType =
                        when (ext) {
                            "mp3", "m4b" -> FileType.AUDIO
                            "json" -> FileType.METADATA
                            else -> FileType.UNKNOWN
                        },
                )
            }
    return CandidateBook(rootRelPath = rel, isFile = false, files = files)
}
