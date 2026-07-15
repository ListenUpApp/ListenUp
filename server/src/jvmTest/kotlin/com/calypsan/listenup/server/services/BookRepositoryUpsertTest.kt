@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
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
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class BookRepositoryUpsertTest :
    FunSpec({

        test("upsert of fresh book inserts row + all children atomically; emits SyncEvent.Created") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val repo = makeRepo(bus)
                runTest {
                    // Seed the contributor/series catalogue rows the junction-row FKs
                    // require. Direct inserts (not resolveOrCreate) so the global
                    // revision counter and the bus stay untouched — the test asserts
                    // the book's own revision is 1 and its Created event is first.
                    sql.transaction {
                        sql.seedContributor("c1", "Brandon Sanderson")
                        sql.seedContributor("c2", "Michael Kramer")
                        sql.seedSeries("s1", "Stormlight Archive")
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
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
                runTest {
                    sql.transaction {
                        sql.seedContributor("c1", "Brandon Sanderson")
                        sql.seedContributor("c2", "Michael Kramer")
                        sql.seedSeries("s1", "Stormlight Archive")
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
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
                runTest {
                    sql.transaction {
                        sql.seedContributor("c1", "Brandon Sanderson")
                        sql.seedSeries("s1", "Stormlight Archive")
                    }
                    repo.upsert(
                        bookPayloadFixture(
                            id = "b1",
                            title = "Way of Kings",
                            contributors = listOf(contributor("c1", "Brandon Sanderson", "author")),
                            series = listOf(series("s1", "Stormlight Archive", "1")),
                        ),
                    )

                    val mappedRowid = sql.bookSearchQueries.selectRowidForBook("b1").executeAsOne()

                    driver.ftsRowids("SELECT rowid FROM book_search WHERE book_search MATCH 'Kings' ORDER BY rank") shouldBe
                        listOf(mappedRowid)

                    // Also search by contributor name — confirms FTS contributor_names column populated.
                    driver.ftsRowids(
                        "SELECT rowid FROM book_search WHERE book_search MATCH 'Sanderson' ORDER BY rank",
                    ) shouldBe listOf(mappedRowid)
                }
            }
        }

        test("upsertFromAnalyzed persists and round-trips hasScanWarning") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
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

        test("findById returns book with null cover when cover_source column holds an unrecognised value") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
                runTest {
                    // Insert a book row directly with a bogus cover_source value that
                    // is not a valid CoverSource enum constant.  This simulates a
                    // partially-migrated or corrupt row.
                    sql.transaction {
                        sql.booksQueries.insert(
                            id = "corrupt-cover",
                            library_id = "test-library",
                            folder_id = "test-folder",
                            title = "Corrupt Cover Book",
                            sort_title = null,
                            subtitle = null,
                            description = null,
                            publish_year = null,
                            publisher = null,
                            language = null,
                            isbn = null,
                            asin = null,
                            abridged = 0L,
                            explicit = 0L,
                            has_scan_warning = 0L,
                            total_duration = 0L,
                            cover_source = "TOTALLY_INVALID_VALUE",
                            cover_path = null,
                            cover_hash = "somehash",
                            field_provenance = "{}",
                            root_rel_path = "books/corrupt-cover",
                            inode = null,
                            scanned_at = 1_730_000_000_000L,
                            revision = 1L,
                            created_at = 0L,
                            updated_at = 0L,
                            deleted_at = null,
                            client_op_id = null,
                        )
                    }

                    val payload = repo.findById(BookId("corrupt-cover"))

                    // The row is readable — corrupt cover_source falls back to null
                    // rather than throwing IllegalArgumentException.
                    payload shouldNotBe null
                    payload!!.title shouldBe "Corrupt Cover Book"
                    payload.cover shouldBe null
                }
            }
        }

        test("batch child writes preserve ordinal order and collapse duplicate contributors/series") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
                runTest {
                    sql.transaction {
                        sql.seedContributor("c1", "Brandon Sanderson")
                        sql.seedSeries("s1", "Stormlight Archive")
                    }
                    val payload =
                        bookPayloadFixture(
                            id = "b1",
                            title = "Way of Kings",
                            // Same (id, role) twice — distinctBy collapses to one junction row;
                            // without it the (book_id, contributor_id, role) PK aborts the ingest.
                            contributors =
                                listOf(
                                    contributor("c1", "Brandon Sanderson", "author"),
                                    contributor("c1", "B. Sanderson", "author"),
                                ),
                            // Same series id twice — distinctBy collapses to one membership.
                            series =
                                listOf(
                                    series("s1", "Stormlight Archive", "1"),
                                    series("s1", "Stormlight Archive", "2"),
                                ),
                            // Multiple audio files — read-back must preserve insertion order via ordinal.
                            audioFiles =
                                listOf(
                                    audioFile("af1", "01.m4b", 1_000L, 1024L),
                                    audioFile("af2", "02.m4b", 2_000L, 2048L),
                                    audioFile("af3", "03.m4b", 3_000L, 3072L),
                                ),
                        )

                    val result = repo.upsert(payload)

                    result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    val saved = result.data
                    saved.contributors.size shouldBe 1
                    saved.contributors[0].id shouldBe "c1"
                    saved.series.size shouldBe 1
                    saved.series[0].id shouldBe "s1"
                    saved.series[0].sequence shouldBe "1"
                    saved.audioFiles.map { it.id } shouldBe listOf("af1", "af2", "af3")

                    // Re-read through the read path to confirm ordinal order is persisted, not just
                    // echoed from the write payload.
                    val reread = repo.findById(BookId("b1"))
                    reread shouldNotBe null
                    reread!!.audioFiles.map { it.id } shouldBe listOf("af1", "af2", "af3")
                }
            }
        }

        test("re-scanning an unchanged book does not bump its revision") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
                runTest {
                    val analyzed = analyzedFixture(rootRelPath = "books/b-idempotent", hasScanWarning = false)

                    val first =
                        repo.upsertFromAnalyzed(
                            BookId("b-idempotent"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzed,
                        )
                    first.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    val r1 = first.data.revision

                    // Second call with identical analyzed output — must NOT bump.
                    val second =
                        repo.upsertFromAnalyzed(
                            BookId("b-idempotent"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            analyzed,
                        )
                    second.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    second.data.revision shouldBe r1

                    // Cross-check via a fresh read — the stored revision must also be unchanged.
                    val stored = repo.findById(BookId("b-idempotent"))
                    stored shouldNotBe null
                    stored!!.revision shouldBe r1
                }
            }
        }

        test("a changed book bumps its revision on re-scan") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
                runTest {
                    val original = analyzedFixture(rootRelPath = "books/b-changed", hasScanWarning = false)

                    val first =
                        repo.upsertFromAnalyzed(
                            BookId("b-changed"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            original,
                        )
                    first.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    val r1 = first.data.revision

                    // Produce a changed analyzed by altering the title via a different rootRelPath
                    // (the fixture's title is derived from the last segment of rootRelPath).
                    val changed = original.copy(title = "A Different Title")

                    val second =
                        repo.upsertFromAnalyzed(
                            BookId("b-changed"),
                            LibraryId("test-library"),
                            FolderId("test-folder"),
                            changed,
                        )
                    second.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    second.data.revision shouldBeGreaterThan r1
                }
            }
        }

        test("update re-uses existing rowid; book_search has exactly one row per book") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo = makeRepo()
                runTest {
                    repo.upsert(bookPayloadFixture(id = "b1", title = "Old Title"))
                    val firstRowid = sql.bookSearchQueries.selectRowidForBook("b1").executeAsOne()

                    repo.upsert(bookPayloadFixture(id = "b1", title = "New Title"))

                    // Mapping unchanged.
                    sql.bookSearchQueries.selectRowidForBook("b1").executeAsOne() shouldBe firstRowid

                    // Old title no longer matches; new title does.
                    driver.ftsRowids(
                        "SELECT rowid FROM book_search WHERE book_search MATCH 'Old' ORDER BY rank",
                    ) shouldBe emptyList()
                    driver.ftsRowids(
                        "SELECT rowid FROM book_search WHERE book_search MATCH 'New' ORDER BY rank",
                    ) shouldBe listOf(firstRowid)
                }
            }
        }
    })

// --- Fixtures ---------------------------------------------------------------

private fun SqlTestDatabases.makeRepo(bus: ChangeBus = ChangeBus()): BookRepository {
    val syncRegistry = SyncRegistry()
    return BookRepository(
        db = sql,
        driver = driver,
        bus = bus,
        registry = syncRegistry,
        contributorRepository = ContributorRepository(sql, bus, syncRegistry),
        seriesRepository = SeriesRepository(sql, bus, syncRegistry),
        genreRepository = GenreRepository(sql, bus, syncRegistry),
    )
}

/** Runs a raw FTS5 MATCH query and collects the matched rowids in rank order. */
private fun SqlDriver.ftsRowids(query: String): List<Long> {
    val result = mutableListOf<Long>()
    executeQuery(
        identifier = null,
        sql = query,
        parameters = 0,
        mapper = { cursor ->
            while (cursor.next().value) {
                cursor.getLong(0)?.let { result += it }
            }
            QueryResult.Value(Unit)
        },
    )
    return result
}

/**
 * Seeds one `contributors` row directly. `replaceContributors` requires the
 * contributor id to pre-exist (its junction-row FK); these inserts satisfy that
 * without touching the global revision counter or the change bus — so tests can
 * still assert on the book's own revision and Created event.
 *
 * Must be called inside a `transaction { }` block.
 */
private fun ListenUpDatabase.seedContributor(
    id: String,
    name: String,
) {
    contributorsQueries.insert(
        id = id,
        normalized_name = name.lowercase().trim(),
        name = name,
        sort_name = null,
        revision = 0L,
        created_at = 0L,
        updated_at = 0L,
        deleted_at = null,
        client_op_id = null,
        asin = null,
        description = null,
        image_path = null,
        birth_date = null,
        death_date = null,
        website = null,
    )
}

/** Seeds one `book_series` row directly — see [seedContributor]. */
private fun ListenUpDatabase.seedSeries(
    id: String,
    name: String,
) {
    seriesQueries.insert(
        id = id,
        normalized_name = name.lowercase().trim(),
        name = name,
        sort_name = null,
        revision = 0L,
        created_at = 0L,
        updated_at = 0L,
        deleted_at = null,
        client_op_id = null,
        asin = null,
        description = null,
        cover_path = null,
    )
}

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
