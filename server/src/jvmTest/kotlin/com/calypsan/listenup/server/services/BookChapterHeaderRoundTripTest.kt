package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class BookChapterHeaderRoundTripTest :
    FunSpec({
        test("writePayload persists partTitle and bookTitle and readPayload returns them") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(sql, bus, syncRegistry)
                val seriesRepo = SeriesRepository(sql, bus, syncRegistry)
                val genreRepo = GenreRepository(sql, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = sql,
                        driver = driver,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                        genreRepository = genreRepo,
                    )
                runTest {
                    repo.upsert(bookFixtureWithHeaders(id = "b-hdr", title = "Stormlight Archive"))
                    val readBackPayload = repo.findById(BookId("b-hdr"))!!
                    val chapters = readBackPayload.chapters
                    chapters.map { it.id } shouldBe listOf("c1", "c2")
                    chapters.first { it.id == "c1" }.partTitle shouldBe "Part One"
                    chapters.first { it.id == "c1" }.bookTitle shouldBe "Book I"
                    chapters.first { it.id == "c2" }.partTitle shouldBe null
                    chapters.first { it.id == "c2" }.bookTitle shouldBe null
                }
            }
        }
    })

private fun bookFixtureWithHeaders(
    id: String,
    title: String,
    rootRelPath: String = "Sanderson/$id",
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = rootRelPath,
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af-$id",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(
                    id = "c1",
                    title = "Prologue",
                    duration = 1_800_000L,
                    startTime = 0L,
                    partTitle = "Part One",
                    bookTitle = "Book I",
                ),
                BookChapterPayload(
                    id = "c2",
                    title = "Chapter 1",
                    duration = 1_800_000L,
                    startTime = 1_800_000L,
                    partTitle = null,
                    bookTitle = null,
                ),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
