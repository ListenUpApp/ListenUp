@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.QueryResult

import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.EntityRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.sync.WorldEventRepository
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import com.calypsan.listenup.server.testing.rootPrincipal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class SeriesServiceImplTest :
    FunSpec({

        test("getSeries returns Success with the payload for an existing series") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(this)
                val service = deps.service
                val seriesRepo = deps.seriesRepo
                runTest {
                    val id = seriesRepo.resolveOrCreate("The Stormlight Archive")

                    val result = service.getSeries(id)

                    val success = result.shouldBeInstanceOf<AppResult.Success<SeriesSyncPayload?>>()
                    success.data shouldNotBe null
                    success.data!!.id shouldBe id.value
                    success.data!!.name shouldBe "The Stormlight Archive"
                }
            }
        }

        test("getSeries returns AppResult.Success(null) for a non-existent series id") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val service = makeSeriesServiceAndDeps(this).service
                runTest {
                    val result = service.getSeries(SeriesId("does-not-exist"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<SeriesSyncPayload?>>()
                    success.data shouldBe null
                }
            }
        }

        test("listBooksBySeries returns all books belonging to the series in position order") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(this)
                val service = deps.service
                val seriesRepo = deps.seriesRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val seriesId = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    bookRepo.upsert(bookFixtureWithSeries("b1", "The Way of Kings", seriesId, "1"))
                    bookRepo.upsert(bookFixtureWithSeries("b2", "Words of Radiance", seriesId, "2", rootRelPath = "WoR"))

                    val result = service.listBooksBySeries(seriesId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data shouldHaveSize 2
                }
            }
        }

        test("listBooksBySeries returns empty list when series has no books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(this)
                val service = deps.service
                val seriesRepo = deps.seriesRepo
                runTest {
                    val seriesId = seriesRepo.resolveOrCreate("Empty Series")

                    val result = service.listBooksBySeries(seriesId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data.shouldBeEmpty()
                }
            }
        }

        // ── updateSeries ───────────────────────────────────────────────────────

        test("updateSeries applies the name patch when the series exists") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(this)
                val service = deps.service
                val seriesRepo = deps.seriesRepo
                runTest {
                    val id = seriesRepo.resolveOrCreate("The Stormlight Archive")

                    val result = service.updateSeries(id, SeriesUpdate(name = "Stormlight"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val reread = seriesRepo.findById(id.value)
                    reread.shouldNotBeNull()
                    reread.name shouldBe "Stormlight"
                }
            }
        }

        test("updateSeries triggers FTS reindex for all linked books when the name changes") {
            withSqlDatabase {
                val dbs = this
                sql.seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(dbs)
                val service = deps.service
                val seriesRepo = deps.seriesRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val seriesId = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    bookRepo.upsert(bookFixtureWithSeries("b1", "The Way of Kings", seriesId, "1"))
                    bookRepo.upsert(bookFixtureWithSeries("b2", "Words of Radiance", seriesId, "2", rootRelPath = "WoR"))
                    val rowidB1 = lookupFtsRowidForSeries(dbs, "b1")
                    val rowidB2 = lookupFtsRowidForSeries(dbs, "b2")
                    // Plant a sentinel series_names so the test can prove a real reindex occurred.
                    overwriteFtsSeriesNames(dbs, rowid = rowidB1, sentinel = "SENTINELNOTOVERWRITTEN")
                    overwriteFtsSeriesNames(dbs, rowid = rowidB2, sentinel = "SENTINELNOTOVERWRITTEN")

                    val result = service.updateSeries(seriesId, SeriesUpdate(name = "Stormlight"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    // Both FTS rows should match the new series name (sentinel was overwritten by reindex).
                    ftsSeriesNamesMatch(dbs, rowidB1, "Stormlight") shouldBe true
                    ftsSeriesNamesMatch(dbs, rowidB2, "Stormlight") shouldBe true
                    ftsSeriesNamesMatch(dbs, rowidB1, "SENTINELNOTOVERWRITTEN") shouldBe false
                }
            }
        }

        test("updateSeries does NOT trigger FTS reindex when only non-name fields change") {
            withSqlDatabase {
                val dbs = this
                sql.seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(dbs)
                val service = deps.service
                val seriesRepo = deps.seriesRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val seriesId = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    bookRepo.upsert(bookFixtureWithSeries("b1", "The Way of Kings", seriesId, "1"))
                    val rowidB1 = lookupFtsRowidForSeries(dbs, "b1")
                    // Tripwire: if the implementation reindexes when it shouldn't, the
                    // sentinel will be overwritten with the live series name.
                    overwriteFtsSeriesNames(dbs, rowid = rowidB1, sentinel = "SENTINELNOTOVERWRITTEN")

                    val result =
                        service.updateSeries(
                            seriesId,
                            SeriesUpdate(description = "Epic fantasy by Brandon Sanderson"),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    // Sentinel must still match — reindex was skipped.
                    ftsSeriesNamesMatch(dbs, rowidB1, "SENTINELNOTOVERWRITTEN") shouldBe true
                    ftsSeriesNamesMatch(dbs, rowidB1, "Stormlight") shouldBe false
                    // Description was applied.
                    val reread = seriesRepo.findById(seriesId.value)
                    reread.shouldNotBeNull()
                    reread.description shouldBe "Epic fantasy by Brandon Sanderson"
                }
            }
        }

        test("updateSeries returns SeriesError.NotFound when the series does not exist") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val service = makeSeriesServiceAndDeps(this).service
                runTest {
                    val result =
                        service.updateSeries(SeriesId("ghost"), SeriesUpdate(name = "Anything"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<SeriesError.NotFound>()
                }
            }
        }

        // ── deleteSeries ───────────────────────────────────────────────────────

        test("deleteSeries cascades — drops junctions, re-upserts affected books, soft-deletes the series") {
            withSqlDatabase {
                val dbs = this
                sql.seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(dbs)
                val service = deps.service
                val seriesRepo = deps.seriesRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val targetSeriesId = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    val otherSeriesId = seriesRepo.resolveOrCreate("Mistborn")
                    // b1 is in both series; b2 is only in the target series.
                    bookRepo.upsert(bookFixtureWithTwoSeries("b1", "The Way of Kings", targetSeriesId, otherSeriesId))
                    bookRepo.upsert(bookFixtureWithSeries("b2", "Words of Radiance", targetSeriesId, "2", rootRelPath = "WoR"))
                    val initialB1 = bookRepo.findById(BookId("b1"))!!
                    val initialB2 = bookRepo.findById(BookId("b2"))!!

                    val result = service.deleteSeries(targetSeriesId)

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Junction rows for the deleted series are gone.
                    val remainingBookIds = readBookIdsForSeries(dbs, targetSeriesId.value)
                    remainingBookIds.shouldBeEmpty()

                    // b1 was re-upserted: revision bumped, target series stripped, other series preserved.
                    val updatedB1 = bookRepo.findById(BookId("b1"))!!
                    updatedB1.revision shouldNotBe initialB1.revision
                    updatedB1.series.map { it.id } shouldBe listOf(otherSeriesId.value)

                    // b2 was re-upserted: revision bumped, series list empty.
                    val updatedB2 = bookRepo.findById(BookId("b2"))!!
                    updatedB2.revision shouldNotBe initialB2.revision
                    updatedB2.series.shouldBeEmpty()

                    // Series is soft-deleted (deletedAt is set; findById bypasses the tombstone filter).
                    val tombstone = seriesRepo.findById(targetSeriesId.value)
                    tombstone.shouldNotBeNull()
                    tombstone.deletedAt shouldNotBe null
                }
            }
        }

        test("deleteSeries succeeds when the series has no linked books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(this)
                val service = deps.service
                val seriesRepo = deps.seriesRepo
                runTest {
                    val targetId = seriesRepo.resolveOrCreate("Empty Series")

                    val result = service.deleteSeries(targetId)

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val tombstone = seriesRepo.findById(targetId.value)
                    tombstone.shouldNotBeNull()
                    tombstone.deletedAt shouldNotBe null
                }
            }
        }

        test("deleteSeries returns SeriesError.NotFound when the series does not exist") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val service = makeSeriesServiceAndDeps(this).service
                runTest {
                    val result = service.deleteSeries(SeriesId("ghost"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<SeriesError.NotFound>()
                }
            }
        }
    })

private data class SeriesServiceDeps(
    val service: SeriesServiceImpl,
    val seriesRepo: SeriesRepository,
    val bookRepo: BookRepository,
    val reindexer: BookSearchReindexer,
)

private fun makeSeriesServiceAndDeps(dbs: SqlTestDatabases): SeriesServiceDeps {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(db = dbs.sql, bus = bus, registry = syncRegistry)
    val seriesRepo = SeriesRepository(db = dbs.sql, bus = bus, registry = syncRegistry)
    val bookRepo =
        BookRepository(
            db = dbs.sql,
            driver = dbs.driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(db = dbs.sql, bus = bus, registry = syncRegistry),
        )
    val tagRepo = TagRepository(db = dbs.sql, bus = bus, registry = syncRegistry)
    val bookTagRepo = BookTagRepository(db = dbs.sql, bus = bus, registry = syncRegistry)
    val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, dbs.sql, dbs.driver)
    val service =
        SeriesServiceImpl(
            seriesRepo = seriesRepo,
            bookRepo = bookRepo,
            entityRepo = EntityRepository(dbs.sql, bus, syncRegistry),
            worldEventRepo = WorldEventRepository(dbs.sql, bus, syncRegistry),
            reindexer = reindexer,
            sqlDb = dbs.sql,
            accessPolicy = BookAccessPolicy(dbs.sql, dbs.driver),
            principal = rootPrincipal(),
        )
    return SeriesServiceDeps(service, seriesRepo, bookRepo, reindexer)
}

/**
 * Reads the FTS rowid that [BookRepository.upsert] allocated for [bookId]
 * via `book_search_map`. Books-C1 tests need this to address the FTS row
 * created automatically by the books pipeline.
 */
private suspend fun lookupFtsRowidForSeries(
    dbs: SqlTestDatabases,
    bookId: String,
): Int =
    withContext(Dispatchers.IO) {
        dbs.sql.bookSearchQueries
            .selectRowidForBook(bookId)
            .executeAsOneOrNull()
            ?.toInt()
            ?: error("No book_search_map row found for bookId=$bookId")
    }

/**
 * Replaces the `series_names` cell of the FTS row at [rowid] with a sentinel
 * value. Acts as a tripwire so tests can prove whether a real reindex re-read
 * the source tables (overwriting the sentinel) or skipped (sentinel survives).
 *
 * `book_search` is contentless_delete=1, so the only safe mutation idiom is
 * DELETE + re-INSERT of the entire row.
 */
private suspend fun overwriteFtsSeriesNames(
    dbs: SqlTestDatabases,
    rowid: Int,
    sentinel: String,
) {
    withContext(Dispatchers.IO) {
        dbs.driver.execute(identifier = null, sql = "DELETE FROM book_search WHERE rowid = $rowid", parameters = 0)
        dbs.driver.execute(
            identifier = null,
            sql =
                "INSERT INTO book_search(rowid, title, subtitle, description, contributor_names, series_names, tags) " +
                    "VALUES ($rowid, ?, '', '', '', ?, '')",
            parameters = 2,
            binders = {
                bindString(0, "Test Book b$rowid")
                bindString(1, sentinel)
            },
        )
    }
}

/**
 * Returns true if a MATCH on `series_names` for [searchTerm] finds [rowid].
 *
 * Uses a column-specific MATCH so the assertion is scoped to series_names
 * only — not a cross-column hit.
 */
private suspend fun ftsSeriesNamesMatch(
    dbs: SqlTestDatabases,
    rowid: Int,
    searchTerm: String,
): Boolean {
    val dq = '"'
    val quotedTerm = "$dq${searchTerm.replace("$dq", "$dq$dq")}$dq"
    return withContext(Dispatchers.IO) {
        dbs.driver
            .executeQuery(
                identifier = null,
                sql = "SELECT rowid FROM book_search WHERE series_names MATCH ? AND rowid = ?",
                mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                parameters = 2,
                binders = {
                    bindString(0, quotedTerm)
                    bindLong(1, rowid.toLong())
                },
            ).value
    }
}

/** Distinct book IDs currently linked to [seriesId] via any junction row. */
private suspend fun readBookIdsForSeries(
    dbs: SqlTestDatabases,
    seriesId: String,
): List<String> =
    withContext(Dispatchers.IO) {
        dbs.sql.bookSeriesMembershipsQueries
            .bookIdsForSeries(seriesId)
            .executeAsList()
    }

private fun bookFixtureWithSeries(
    id: String,
    title: String,
    seriesId: SeriesId,
    sequence: String,
    rootRelPath: String = "Sanderson/$title",
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
        hasScanWarning = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = rootRelPath,
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series =
            listOf(
                BookSeriesPayload(
                    id = seriesId.value,
                    name = "The Stormlight Archive",
                    sequence = sequence,
                ),
            ),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af1",
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
                BookChapterPayload(id = "ch1", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

private fun bookFixtureWithTwoSeries(
    id: String,
    title: String,
    firstSeriesId: SeriesId,
    secondSeriesId: SeriesId,
): BookSyncPayload =
    bookFixtureWithSeries(id, title, firstSeriesId, "1").copy(
        series =
            listOf(
                BookSeriesPayload(
                    id = firstSeriesId.value,
                    name = "The Stormlight Archive",
                    sequence = "1",
                ),
                BookSeriesPayload(
                    id = secondSeriesId.value,
                    name = "Mistborn",
                    sequence = "1",
                ),
            ),
    )
