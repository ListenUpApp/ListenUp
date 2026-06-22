package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class StickyChaptersOnRescanTest :
    FunSpec({

        test("USER chapter set survives a scanner re-ingest with EMBEDDED chapters") {
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
                    // First upsert: user-edited chapter set
                    repo.upsert(
                        bookFixture("bx").copy(
                            chapters =
                                listOf(
                                    BookChapterPayload(id = "ch-p", title = "Prologue", duration = 600_000L, startTime = 0L),
                                    BookChapterPayload(id = "ch-a", title = "Act One", duration = 2_400_000L, startTime = 600_000L),
                                ),
                            chapterSource = ChapterSource.USER,
                        ),
                    )

                    // Second upsert: simulated rescan with EMBEDDED chapters — must NOT clobber USER set
                    repo.upsert(
                        bookFixture("bx").copy(
                            chapters =
                                listOf(
                                    BookChapterPayload(id = "ch-1", title = "Ch1", duration = 100_000L, startTime = 0L),
                                    BookChapterPayload(id = "ch-2", title = "Ch2", duration = 100_000L, startTime = 100_000L),
                                    BookChapterPayload(id = "ch-3", title = "Ch3", duration = 100_000L, startTime = 200_000L),
                                ),
                            chapterSource = ChapterSource.EMBEDDED,
                        ),
                    )

                    val readback = repo.findById(BookId("bx"))!!
                    readback.chapterSource shouldBe ChapterSource.USER
                    readback.chapters shouldHaveSize 2
                    readback.chapters[0].title shouldBe "Prologue"
                    readback.chapters[1].title shouldBe "Act One"
                }
            }
        }

        test("USER chapter headers (partTitle/bookTitle) survive a rescan") {
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
                    // First upsert: USER chapter set with Book/Part headers on the opening chapter
                    repo.upsert(
                        bookFixture("bx").copy(
                            chapters =
                                listOf(
                                    BookChapterPayload(
                                        id = "ch-p",
                                        title = "Prologue",
                                        duration = 600_000L,
                                        startTime = 0L,
                                        partTitle = "Part One",
                                        bookTitle = "Book I",
                                    ),
                                    BookChapterPayload(id = "ch-a", title = "Act One", duration = 2_400_000L, startTime = 600_000L),
                                ),
                            chapterSource = ChapterSource.USER,
                        ),
                    )

                    // Second upsert: simulated rescan with EMBEDDED chapters — must NOT clobber USER set or its headers
                    repo.upsert(
                        bookFixture("bx").copy(
                            chapters =
                                listOf(
                                    BookChapterPayload(id = "ch-1", title = "Ch1", duration = 100_000L, startTime = 0L),
                                    BookChapterPayload(id = "ch-2", title = "Ch2", duration = 100_000L, startTime = 100_000L),
                                ),
                            chapterSource = ChapterSource.EMBEDDED,
                        ),
                    )

                    val readback = repo.findById(BookId("bx"))!!
                    readback.chapterSource shouldBe ChapterSource.USER
                    readback.chapters shouldHaveSize 2
                    val openingChapter = readback.chapters.first { it.id == "ch-p" }
                    openingChapter.partTitle shouldBe "Part One"
                    openingChapter.bookTitle shouldBe "Book I"
                }
            }
        }

        test("USER chapter set is overwritten by a subsequent USER edit") {
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
                    // First USER edit: [A@0]
                    repo.upsert(
                        bookFixture("bx").copy(
                            chapters =
                                listOf(
                                    BookChapterPayload(id = "ch-a", title = "A", duration = 3_600_000L, startTime = 0L),
                                ),
                            chapterSource = ChapterSource.USER,
                        ),
                    )

                    // Second USER edit: [B@0, C@50_000] — must overwrite
                    repo.upsert(
                        bookFixture("bx").copy(
                            chapters =
                                listOf(
                                    BookChapterPayload(id = "ch-b", title = "B", duration = 50_000L, startTime = 0L),
                                    BookChapterPayload(id = "ch-c", title = "C", duration = 3_550_000L, startTime = 50_000L),
                                ),
                            chapterSource = ChapterSource.USER,
                        ),
                    )

                    val readback = repo.findById(BookId("bx"))!!
                    readback.chapterSource shouldBe ChapterSource.USER
                    readback.chapters shouldHaveSize 2
                    readback.chapters[0].title shouldBe "B"
                    readback.chapters[1].title shouldBe "C"
                }
            }
        }
    })

private fun bookFixture(
    id: String,
    rootRelPath: String = "Sanderson/$id",
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Test Book $id",
        sortTitle = "Test Book $id",
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
                BookChapterPayload(id = "ch-$id", title = "Prologue", duration = 3_600_000L, startTime = 0L),
            ),
        chapterSource = ChapterSource.EMBEDDED,
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
