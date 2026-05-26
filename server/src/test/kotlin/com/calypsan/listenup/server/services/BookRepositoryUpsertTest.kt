@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.BookSearchMapTable
import com.calypsan.listenup.server.db.BookSeriesTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class BookRepositoryUpsertTest :
    FunSpec({

        test("upsert of fresh book inserts row + all children atomically; emits SyncEvent.Created") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = ContributorRepository(db, bus, syncRegistry),
                        seriesRepository = SeriesRepository(db, bus, syncRegistry),
                    )
                runTest {
                    // Seed the contributor/series catalogue rows the junction-row FKs
                    // require. Direct inserts (not resolveOrCreate) so the global
                    // revision counter and the bus stay untouched — the test asserts
                    // the book's own revision is 1 and its Created event is first.
                    transaction(db) {
                        seedContributor("c1", "Brandon Sanderson")
                        seedContributor("c2", "Michael Kramer")
                        seedSeries("s1", "Stormlight Archive")
                    }
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    val payload =
                        bookPayloadFixture(
                            id = "b1",
                            title = "Way of Kings",
                            contributors =
                                listOf(
                                    contributor("c1", "Brandon Sanderson", "author"),
                                    contributor("c2", "Michael Kramer", "narrator"),
                                ),
                            series = listOf(series("s1", "Stormlight Archive", "1")),
                            chapters =
                                listOf(
                                    chapter("ch1", "Prologue", 1_200_000L, 0L),
                                    chapter("ch2", "Chapter 1", 1_800_000L, 1_200_000L),
                                ),
                            audioFiles = listOf(audioFile("af1", "01.m4b", 162_000_000L, 200_000_000L)),
                            cover = CoverPayload(source = CoverSource.FILESYSTEM, hash = "deadbeef"),
                        )

                    val result = repo.upsert(payload, clientOpId = "op-1")

                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    val saved = result.data
                    saved.id shouldBe "b1"
                    saved.title shouldBe "Way of Kings"
                    saved.contributors.size shouldBe 2
                    saved.series.size shouldBe 1
                    saved.chapters.size shouldBe 2
                    saved.audioFiles.size shouldBe 1
                    saved.revision shouldBe 1L
                    saved.cover?.hash shouldBe "deadbeef"

                    val busEvent = deferred.await()
                    busEvent.repo.domainName shouldBe "books"
                    val event = busEvent.event
                    event.shouldBeInstanceOf<SyncEvent.Created<BookSyncPayload>>()
                    event.id shouldBe "b1"
                    event.clientOpId shouldBe "op-1"
                }
            }
        }

        test("upsert replaces child rows wholesale on second call") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = ContributorRepository(db, bus, syncRegistry),
                        seriesRepository = SeriesRepository(db, bus, syncRegistry),
                    )
                runTest {
                    transaction(db) {
                        seedContributor("c1", "Brandon Sanderson")
                        seedContributor("c2", "Michael Kramer")
                        seedSeries("s1", "Stormlight Archive")
                    }
                    val v1 =
                        bookPayloadFixture(
                            id = "b1",
                            title = "Way of Kings",
                            contributors =
                                listOf(
                                    contributor("c1", "Brandon Sanderson", "author"),
                                    contributor("c2", "Michael Kramer", "narrator"),
                                ),
                            series = listOf(series("s1", "Stormlight Archive", "1")),
                            chapters =
                                listOf(
                                    chapter("ch1", "Prologue", 1_000L, 0L),
                                    chapter("ch2", "C1", 1_000L, 1_000L),
                                    chapter("ch3", "C2", 1_000L, 2_000L),
                                    chapter("ch4", "C3", 1_000L, 3_000L),
                                    chapter("ch5", "C4", 1_000L, 4_000L),
                                ),
                            audioFiles = listOf(audioFile("af1", "01.m4b", 5_000L, 1024L)),
                        )
                    repo.upsert(v1)

                    val v2 =
                        bookPayloadFixture(
                            id = "b1",
                            title = "Way of Kings",
                            contributors = listOf(contributor("c1", "Brandon Sanderson", "author")),
                            series = listOf(series("s1", "Stormlight Archive", "1")),
                            chapters =
                                listOf(
                                    chapter("nch1", "Prologue", 1_000L, 0L),
                                    chapter("nch2", "C1", 1_000L, 1_000L),
                                    chapter("nch3", "C2", 1_000L, 2_000L),
                                ),
                            audioFiles = listOf(audioFile("af1", "01.m4b", 3_000L, 1024L)),
                        )
                    val result = repo.upsert(v2)

                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    val saved = result.data
                    saved.chapters.size shouldBe 3
                    saved.chapters.map { it.id } shouldBe listOf("nch1", "nch2", "nch3")
                    saved.contributors.size shouldBe 1
                    saved.contributors[0].role shouldBe "author"
                }
            }
        }

        test("FTS row is upserted in book_search and mapped via book_search_map") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = ContributorRepository(db, bus, syncRegistry),
                        seriesRepository = SeriesRepository(db, bus, syncRegistry),
                    )
                runTest {
                    transaction(db) {
                        seedContributor("c1", "Brandon Sanderson")
                        seedSeries("s1", "Stormlight Archive")
                    }
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "Way of Kings",
                            contributors = listOf(contributor("c1", "Brandon Sanderson", "author")),
                            series = listOf(series("s1", "Stormlight Archive", "1")),
                        ),
                    )

                    transaction(db) {
                        val mappedRowid =
                            BookSearchMapTable
                                .selectAll()
                                .where { BookSearchMapTable.bookId eq "b1" }
                                .first()[BookSearchMapTable.rowid]

                        val hits = mutableListOf<Int>()
                        exec(
                            "SELECT rowid FROM book_search WHERE book_search MATCH 'Kings' ORDER BY rank",
                        ) { rs ->
                            while (rs.next()) hits += rs.getInt(1)
                        }
                        hits shouldBe listOf(mappedRowid)

                        // Also search by contributor name — confirms FTS contributor_names column populated.
                        val byAuthor = mutableListOf<Int>()
                        exec(
                            "SELECT rowid FROM book_search WHERE book_search MATCH 'Sanderson' ORDER BY rank",
                        ) { rs ->
                            while (rs.next()) byAuthor += rs.getInt(1)
                        }
                        byAuthor shouldBe listOf(mappedRowid)
                    }
                }
            }
        }

        test("upsertFromAnalyzed persists and round-trips hasScanWarning") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = ContributorRepository(db, bus, syncRegistry),
                        seriesRepository = SeriesRepository(db, bus, syncRegistry),
                    )
                runTest {
                    val warned =
                        repo.upsertFromAnalyzed(
                            BookId("warned"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzedFixture(rootRelPath = "books/warned", hasScanWarning = true),
                        )
                    warned.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    warned.data.hasScanWarning shouldBe true
                    repo.findById(BookId("warned"))?.hasScanWarning shouldBe true

                    val clean =
                        repo.upsertFromAnalyzed(
                            BookId("clean"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzedFixture(rootRelPath = "books/clean", hasScanWarning = false),
                        )
                    clean.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    clean.data.hasScanWarning shouldBe false
                    repo.findById(BookId("clean"))?.hasScanWarning shouldBe false
                }
            }
        }

        test("update re-uses existing rowid; book_search has exactly one row per book") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val repo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = ContributorRepository(db, bus, syncRegistry),
                        seriesRepository = SeriesRepository(db, bus, syncRegistry),
                    )
                runTest {
                    repo.upsert(bookPayloadFixture(id = "b1", title = "Old Title"))
                    val firstRowid =
                        transaction(db) {
                            BookSearchMapTable
                                .selectAll()
                                .where { BookSearchMapTable.bookId eq "b1" }
                                .first()[BookSearchMapTable.rowid]
                        }

                    repo.upsert(bookPayloadFixture(id = "b1", title = "New Title"))
                    transaction(db) {
                        // Mapping unchanged.
                        BookSearchMapTable
                            .selectAll()
                            .where { BookSearchMapTable.bookId eq "b1" }
                            .first()[BookSearchMapTable.rowid] shouldBe firstRowid

                        // Old title no longer matches; new title does.
                        val oldHits = mutableListOf<Int>()
                        exec("SELECT rowid FROM book_search WHERE book_search MATCH 'Old' ORDER BY rank") { rs ->
                            while (rs.next()) oldHits += rs.getInt(1)
                        }
                        oldHits shouldBe emptyList()

                        val newHits = mutableListOf<Int>()
                        exec("SELECT rowid FROM book_search WHERE book_search MATCH 'New' ORDER BY rank") { rs ->
                            while (rs.next()) newHits += rs.getInt(1)
                        }
                        newHits shouldBe listOf(firstRowid)
                    }
                }
            }
        }
    })

// --- Fixtures ---------------------------------------------------------------

/**
 * Seeds one [ContributorTable] row directly. `replaceContributors` requires the
 * contributor id to pre-exist (its junction-row FK); these inserts satisfy that
 * without touching the global revision counter or the change bus — so tests can
 * still assert on the book's own revision and Created event.
 *
 * Must be called inside a `transaction { }` block.
 */
private fun seedContributor(
    id: String,
    name: String,
) {
    ContributorTable.insert {
        it[ContributorTable.id] = id
        it[ContributorTable.normalizedName] = name.lowercase().trim()
        it[ContributorTable.name] = name
        it[ContributorTable.sortName] = null
        it[ContributorTable.revision] = 0L
        it[ContributorTable.createdAt] = 0L
        it[ContributorTable.updatedAt] = 0L
        it[ContributorTable.deletedAt] = null
        it[ContributorTable.clientOpId] = null
    }
}

/** Seeds one [BookSeriesTable] row directly — see [seedContributor]. */
private fun seedSeries(
    id: String,
    name: String,
) {
    BookSeriesTable.insert {
        it[BookSeriesTable.id] = id
        it[BookSeriesTable.normalizedName] = name.lowercase().trim()
        it[BookSeriesTable.name] = name
        it[BookSeriesTable.sortName] = null
        it[BookSeriesTable.revision] = 0L
        it[BookSeriesTable.createdAt] = 0L
        it[BookSeriesTable.updatedAt] = 0L
        it[BookSeriesTable.deletedAt] = null
        it[BookSeriesTable.clientOpId] = null
    }
}

private fun bookPayloadFixture(
    id: String,
    title: String,
    rootRelPath: String = "books/$id",
    contributors: List<BookContributorPayload> = emptyList(),
    series: List<BookSeriesPayload> = emptyList(),
    chapters: List<BookChapterPayload> = emptyList(),
    audioFiles: List<BookAudioFilePayload> = emptyList(),
    cover: CoverPayload? = null,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = null,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = audioFiles.sumOf { it.duration },
        cover = cover,
        rootRelPath = rootRelPath,
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = contributors,
        series = series,
        audioFiles = audioFiles,
        chapters = chapters,
        // The substrate authors the persisted revision/timestamps; the wire payload
        // values here are placeholders for the test, ignored by writePayload.
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

/**
 * Builds a minimal [AnalyzedBook] anchored at [rootRelPath] with one audio file,
 * carrying the supplied [hasScanWarning] advisory flag.
 */
private fun analyzedFixture(
    rootRelPath: String,
    hasScanWarning: Boolean,
): AnalyzedBook {
    val file =
        FileEntry(
            relPath = "$rootRelPath/01.m4b",
            name = "01.m4b",
            ext = "m4b",
            size = 1024L,
            mtimeMs = 0L,
            inode = null,
            fileType = FileType.AUDIO,
        )
    return AnalyzedBook(
        candidate =
            CandidateBook(
                rootRelPath = rootRelPath,
                isFile = false,
                files = listOf(file),
            ),
        title = rootRelPath.substringAfterLast('/'),
        tracks = listOf(TrackEntry(file = file)),
        hasScanWarning = hasScanWarning,
    )
}

private fun contributor(
    id: String,
    name: String,
    role: String,
    sortName: String? = null,
    creditedAs: String? = null,
): BookContributorPayload =
    BookContributorPayload(
        id = id,
        name = name,
        sortName = sortName,
        role = role,
        creditedAs = creditedAs,
    )

private fun series(
    id: String,
    name: String,
    sequence: String?,
): BookSeriesPayload =
    BookSeriesPayload(
        id = id,
        name = name,
        sequence = sequence,
    )

private fun chapter(
    id: String,
    title: String,
    duration: Long,
    startTime: Long,
): BookChapterPayload =
    BookChapterPayload(
        id = id,
        title = title,
        duration = duration,
        startTime = startTime,
    )

private fun audioFile(
    id: String,
    filename: String,
    duration: Long,
    size: Long,
    format: String = "m4b",
    codec: String = "aac",
    index: Int = 0,
): BookAudioFilePayload =
    BookAudioFilePayload(
        id = id,
        index = index,
        filename = filename,
        format = format,
        codec = codec,
        duration = duration,
        size = size,
    )
