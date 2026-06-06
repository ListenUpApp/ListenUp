@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.domain.embeddedmeta.AudioFormat
import com.calypsan.listenup.domain.embeddedmeta.AudioTags
import com.calypsan.listenup.domain.embeddedmeta.Chapter
import com.calypsan.listenup.domain.embeddedmeta.ChapterSource
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * Unit tests for the pure-function [AnalyzedBookMapper]. No database, no Koin —
 * just shape preservation across the AnalyzedBook → BookSyncPayload mapping that
 * was previously inline in [BookRepository.upsertFromAnalyzed].
 *
 * The repository's [BookRepositoryUpsertTest] / [BookRepositoryResolveTest]
 * exercise the same mapper indirectly through real DB writes; these tests pin
 * the construction logic where it lives now.
 */
class AnalyzedBookMapperTest :
    FunSpec({

        val fixedNow = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val mapper = AnalyzedBookMapper(clock = FixedClock(fixedNow))

        test("should populate identity and defaults when AnalyzedBook is minimal") {
            val analyzed =
                AnalyzedBook(
                    candidate =
                        CandidateBook(
                            rootRelPath = "books/minimal",
                            isFile = false,
                            files = emptyList(),
                        ),
                    title = "Minimal Book",
                )

            val payload =
                mapper.toBookSyncPayload(
                    bookId = BookId("b-minimal"),
                    libraryId = LibraryId("lib-1"),
                    folderId = FolderId("folder-1"),
                    analyzed = analyzed,
                    resolvedContributors = emptyList(),
                    resolvedSeries = emptyList(),
                )

            payload.id shouldBe "b-minimal"
            payload.libraryId shouldBe LibraryId("lib-1")
            payload.folderId shouldBe FolderId("folder-1")
            payload.title shouldBe "Minimal Book"
            payload.sortTitle shouldBe "Minimal Book"
            payload.subtitle shouldBe null
            payload.description shouldBe null
            payload.publishYear shouldBe null
            payload.abridged shouldBe false
            payload.explicit shouldBe false
            payload.hasScanWarning shouldBe false
            payload.totalDuration shouldBe 0L
            payload.cover shouldBe null
            payload.rootRelPath shouldBe "books/minimal"
            payload.inode shouldBe null
            payload.scannedAt shouldBe fixedNow.toEpochMilliseconds()
            payload.contributors shouldBe emptyList()
            payload.series shouldBe emptyList()
            payload.audioFiles shouldBe emptyList()
            payload.chapters shouldBe emptyList()
            payload.revision shouldBe 0L
            payload.updatedAt shouldBe 0L
            payload.createdAt shouldBe 0L
            payload.deletedAt shouldBe null
        }

        test("should reflect the resolved contributor list when AnalyzedBook has one contributor") {
            val analyzed =
                AnalyzedBook(
                    candidate =
                        CandidateBook(
                            rootRelPath = "books/sanderson",
                            isFile = false,
                            files = emptyList(),
                        ),
                    title = "Way of Kings",
                    authors = listOf("Brandon Sanderson"),
                )

            // Caller-supplied resolved list — mimics what BookRepository overlays
            // after resolving names through ContributorRepository.
            val resolvedContributors =
                listOf(
                    BookContributorPayload(
                        id = "contributor-1",
                        name = "Brandon Sanderson",
                        sortName = null,
                        role = "author",
                        creditedAs = null,
                    ),
                )

            val payload =
                mapper.toBookSyncPayload(
                    bookId = BookId("b-sanderson"),
                    libraryId = LibraryId("lib-1"),
                    folderId = FolderId("folder-1"),
                    analyzed = analyzed,
                    resolvedContributors = resolvedContributors,
                    resolvedSeries = emptyList(),
                )

            payload.contributors shouldBe resolvedContributors
        }

        test("should tag authors and narrators with correct roles when contributors list is non-empty") {
            val analyzed =
                AnalyzedBook(
                    candidate =
                        CandidateBook(
                            rootRelPath = "books/x",
                            isFile = false,
                            files = emptyList(),
                        ),
                    title = "X",
                    authors = listOf("Author One", "Author Two"),
                    narrators = listOf("Narrator One"),
                )

            mapper.buildContributors(analyzed) shouldBe
                listOf(
                    BookContributorPayload(
                        id = "",
                        name = "Author One",
                        sortName = null,
                        role = "author",
                        creditedAs = null,
                    ),
                    BookContributorPayload(
                        id = "",
                        name = "Author Two",
                        sortName = null,
                        role = "author",
                        creditedAs = null,
                    ),
                    BookContributorPayload(
                        id = "",
                        name = "Narrator One",
                        sortName = null,
                        role = "narrator",
                        creditedAs = null,
                    ),
                )
        }

        test("buildContributors splits multi-person strings and extracts roles") {
            val analyzed =
                analyzedBook(
                    authors = listOf("Stephen King; Joe Hill - Introduction"),
                    narrators = listOf("Michael Kramer; Kate Reading"),
                )

            val contributors = mapper.buildContributors(analyzed)

            contributors.map { it.name to it.role } shouldBe
                listOf(
                    "Stephen King" to "author",
                    "Joe Hill" to "introduction",
                    "Michael Kramer" to "narrator",
                    "Kate Reading" to "narrator",
                )
        }

        test("buildContributors keeps a person credited as both author and narrator as two rows") {
            val analyzed = analyzedBook(authors = listOf("Brandon Sanderson"), narrators = listOf("Brandon Sanderson"))

            mapper.buildContributors(analyzed).map { it.name to it.role } shouldBe
                listOf("Brandon Sanderson" to "author", "Brandon Sanderson" to "narrator")
        }

        test("buildContributors de-dupes an identical name and role") {
            val analyzed = analyzedBook(authors = listOf("Stephen King, Stephen King"))

            mapper.buildContributors(analyzed).map { it.name to it.role } shouldBe
                listOf("Stephen King" to "author")
        }

        test("should map series entries to payloads with blank ids") {
            val analyzed =
                AnalyzedBook(
                    candidate =
                        CandidateBook(
                            rootRelPath = "books/x",
                            isFile = false,
                            files = emptyList(),
                        ),
                    title = "X",
                    series = listOf(SeriesEntry(name = "Stormlight Archive", sequence = "1")),
                )

            mapper.buildSeries(analyzed) shouldBe
                listOf(
                    BookSeriesPayload(id = "", name = "Stormlight Archive", sequence = "1"),
                )
        }

        test("should assign embedded duration only to the primary track when multiple tracks are present") {
            val firstFile =
                FileEntry(
                    relPath = "books/x/01.m4b",
                    name = "01.m4b",
                    ext = "m4b",
                    size = 1024L,
                    mtimeMs = 0L,
                    inode = 42L,
                    fileType = FileType.AUDIO,
                )
            val secondFile =
                FileEntry(
                    relPath = "books/x/02.m4b",
                    name = "02.m4b",
                    ext = "m4b",
                    size = 2048L,
                    mtimeMs = 0L,
                    inode = 43L,
                    fileType = FileType.AUDIO,
                )
            val analyzed =
                AnalyzedBook(
                    candidate =
                        CandidateBook(
                            rootRelPath = "books/x",
                            isFile = false,
                            files = listOf(firstFile, secondFile),
                        ),
                    title = "X",
                    tracks = listOf(TrackEntry(file = firstFile), TrackEntry(file = secondFile)),
                    embedded = embeddedMeta(durationMs = 12_345L),
                )

            val audioFiles = mapper.buildAudioFiles(analyzed)
            audioFiles.size shouldBe 2
            audioFiles[0].index shouldBe 0
            audioFiles[0].filename shouldBe "01.m4b"
            audioFiles[0].format shouldBe "m4b"
            audioFiles[0].codec shouldBe ""
            audioFiles[0].duration shouldBe 12_345L
            audioFiles[0].size shouldBe 1024L
            audioFiles[1].index shouldBe 1
            audioFiles[1].filename shouldBe "02.m4b"
            audioFiles[1].duration shouldBe 0L
            audioFiles[1].size shouldBe 2048L
        }

        test("should compute chapter duration as end minus start") {
            val analyzed =
                AnalyzedBook(
                    candidate =
                        CandidateBook(
                            rootRelPath = "books/x",
                            isFile = false,
                            files = emptyList(),
                        ),
                    title = "X",
                    chapters =
                        listOf(
                            Chapter(index = 1, title = "Prologue", startMs = 0L, endMs = 60_000L),
                            Chapter(index = 2, title = "Chapter 1", startMs = 60_000L, endMs = 180_000L),
                        ),
                )

            val chapters = mapper.buildChapters(analyzed)
            chapters.size shouldBe 2
            chapters[0].title shouldBe "Prologue"
            chapters[0].startTime shouldBe 0L
            chapters[0].duration shouldBe 60_000L
            chapters[1].title shouldBe "Chapter 1"
            chapters[1].startTime shouldBe 60_000L
            chapters[1].duration shouldBe 120_000L
        }

        test("sortTitle strips a leading article when no embedded sort tag") {
            val analyzed =
                AnalyzedBook(
                    candidate =
                        CandidateBook(
                            rootRelPath = "books/hobbit",
                            isFile = false,
                            files = emptyList(),
                        ),
                    title = "The Hobbit",
                )

            val payload =
                mapper.toBookSyncPayload(
                    bookId = BookId("b-hobbit"),
                    libraryId = LibraryId("lib-1"),
                    folderId = FolderId("folder-1"),
                    analyzed = analyzed,
                    resolvedContributors = emptyList(),
                    resolvedSeries = emptyList(),
                )

            payload.sortTitle shouldBe "Hobbit"
        }

        test("embedded titleSort tag wins over article stripping") {
            val file =
                FileEntry(
                    relPath = "books/hobbit/01.m4b",
                    name = "01.m4b",
                    ext = "m4b",
                    size = 1024L,
                    mtimeMs = 0L,
                    inode = 1L,
                    fileType = FileType.AUDIO,
                )
            val analyzed =
                AnalyzedBook(
                    candidate =
                        CandidateBook(
                            rootRelPath = "books/hobbit",
                            isFile = false,
                            files = listOf(file),
                        ),
                    title = "The Hobbit",
                    tracks = listOf(TrackEntry(file = file)),
                    embedded = embeddedMeta(durationMs = 1_000L, titleSort = "Hobbit, The"),
                )

            val payload =
                mapper.toBookSyncPayload(
                    bookId = BookId("b-hobbit"),
                    libraryId = LibraryId("lib-1"),
                    folderId = FolderId("folder-1"),
                    analyzed = analyzed,
                    resolvedContributors = emptyList(),
                    resolvedSeries = emptyList(),
                )

            payload.sortTitle shouldBe "Hobbit, The"
        }

        test("should carry inode from the first track file") {
            val file =
                FileEntry(
                    relPath = "books/x/01.m4b",
                    name = "01.m4b",
                    ext = "m4b",
                    size = 1024L,
                    mtimeMs = 0L,
                    inode = 999L,
                    fileType = FileType.AUDIO,
                )
            val analyzed =
                AnalyzedBook(
                    candidate =
                        CandidateBook(
                            rootRelPath = "books/x",
                            isFile = false,
                            files = listOf(file),
                        ),
                    title = "X",
                    tracks = listOf(TrackEntry(file = file)),
                    embedded = embeddedMeta(durationMs = 7_777L),
                    hasScanWarning = true,
                )

            val payload =
                mapper.toBookSyncPayload(
                    bookId = BookId("b-1"),
                    libraryId = LibraryId("lib-1"),
                    folderId = FolderId("folder-1"),
                    analyzed = analyzed,
                    resolvedContributors = emptyList(),
                    resolvedSeries = emptyList(),
                )

            payload.inode shouldBe 999L
            payload.totalDuration shouldBe 7_777L
            payload.hasScanWarning shouldBe true
        }
    })

private fun analyzedBook(
    authors: List<String> = emptyList(),
    narrators: List<String> = emptyList(),
): AnalyzedBook =
    AnalyzedBook(
        candidate =
            CandidateBook(
                rootRelPath = "books/x",
                isFile = false,
                files = emptyList(),
            ),
        title = "X",
        authors = authors,
        narrators = narrators,
    )

private fun embeddedMeta(
    durationMs: Long,
    titleSort: String? = null,
): EmbeddedAudioMetadata =
    EmbeddedAudioMetadata(
        format = AudioFormat.Mp3,
        durationMs = durationMs,
        tags =
            AudioTags(
                title = null,
                subtitle = null,
                authors = emptyList(),
                narrators = emptyList(),
                series = emptyList(),
                genres = emptyList(),
                description = null,
                publisher = null,
                publishedYear = null,
                asin = null,
                isbn = null,
                language = null,
                trackNumber = null,
                discNumber = null,
                custom = emptyMap(),
                titleSort = titleSort,
            ),
        chapters = emptyList(),
        chaptersSource = ChapterSource.None,
        artwork = null,
    )
