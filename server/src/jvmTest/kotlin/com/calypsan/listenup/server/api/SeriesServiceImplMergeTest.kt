@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.QueryResult

import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
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
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import com.calypsan.listenup.server.testing.rootPrincipal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Integration tests for [SeriesServiceImpl.mergeSeries] (Books-C2 Task 16).
 *
 * Verifies the full transactional cascade — junction relink, re-upsert of each
 * affected book (bumping revision + emitting `book.Updated`), soft-delete of
 * source, and the post-commit FTS reindex for `book_search.series_names`.
 *
 * Modelled on [ContributorServiceImplMergeTest] and [SeriesServiceImplTest] —
 * real in-memory Flyway-migrated SQLite, repositories wired to the same database.
 * Helpers are file-local per the established precedent.
 */
class SeriesServiceImplMergeTest :
    FunSpec({

        // ── Validation failures ────────────────────────────────────────────────

        test("mergeSeries returns MergeSelfTarget when source equals target") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeMergeSeriesServiceAndDeps(this)
                runTest {
                    val id = deps.seriesRepo.resolveOrCreate("The Stormlight Archive")

                    val result = deps.service.mergeSeries(id, id)

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<SeriesError.MergeSelfTarget>()
                }
            }
        }

        test("mergeSeries returns NotFound when source does not exist") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeMergeSeriesServiceAndDeps(this)
                runTest {
                    val targetId = deps.seriesRepo.resolveOrCreate("Mistborn")

                    val result = deps.service.mergeSeries(SeriesId("missing"), targetId)

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<SeriesError.NotFound>()
                }
            }
        }

        test("mergeSeries returns NotFound when target does not exist") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeMergeSeriesServiceAndDeps(this)
                runTest {
                    val sourceId = deps.seriesRepo.resolveOrCreate("The Stormlight Archive")

                    val result = deps.service.mergeSeries(sourceId, SeriesId("missing"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<SeriesError.NotFound>()
                }
            }
        }

        test("mergeSeries returns NotFound when source is already tombstoned") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeMergeSeriesServiceAndDeps(this)
                runTest {
                    val sourceId = deps.seriesRepo.resolveOrCreate("Source Series")
                    val targetId = deps.seriesRepo.resolveOrCreate("Target Series")
                    deps.seriesRepo.softDelete(sourceId).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val result = deps.service.mergeSeries(sourceId, targetId)

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<SeriesError.NotFound>()
                }
            }
        }

        // ── Happy-path cascade ─────────────────────────────────────────────────

        test("mergeSeries relinks memberships, bumps book revisions, and soft-deletes source") {
            withSqlDatabase {
                val dbs = this
                sql.seedTestLibraryAndFolder()
                val deps = makeMergeSeriesServiceAndDeps(dbs)
                runTest {
                    val sourceId = deps.seriesRepo.resolveOrCreate("Source Series")
                    val targetId = deps.seriesRepo.resolveOrCreate("Target Series")
                    deps.bookRepo.upsert(bookFixtureForSeriesMerge("b1", "Book One", sourceId))
                    deps.bookRepo.upsert(
                        bookFixtureForSeriesMerge("b2", "Book Two", sourceId, rootRelPath = "books/b2"),
                    )
                    val initialB1Rev = deps.bookRepo.findById(BookId("b1"))!!.revision
                    val initialB2Rev = deps.bookRepo.findById(BookId("b2"))!!.revision

                    val result = deps.service.mergeSeries(sourceId, targetId)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Memberships re-linked to target.
                    bookIdsForSeriesInTest(dbs, targetId.value) shouldBe listOf("b1", "b2").sorted()
                    bookIdsForSeriesInTest(dbs, sourceId.value) shouldBe emptyList()

                    // Each affected book was re-upserted → revision bumped → SSE emission.
                    deps.bookRepo.findById(BookId("b1"))!!.revision shouldNotBe initialB1Rev
                    deps.bookRepo.findById(BookId("b2"))!!.revision shouldNotBe initialB2Rev

                    // Source is soft-deleted.
                    val sourceAfter = deps.seriesRepo.findById(sourceId.value)
                    sourceAfter shouldNotBe null
                    sourceAfter!!.deletedAt shouldNotBe null
                }
            }
        }

        test("mergeSeries succeeds when source has no books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeMergeSeriesServiceAndDeps(this)
                runTest {
                    val sourceId = deps.seriesRepo.resolveOrCreate("Empty Source")
                    val targetId = deps.seriesRepo.resolveOrCreate("Target Series")

                    val result = deps.service.mergeSeries(sourceId, targetId)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Source soft-deleted even with no books.
                    deps.seriesRepo.findById(sourceId.value)!!.deletedAt shouldNotBe null
                    // Target untouched.
                    deps.seriesRepo.findById(targetId.value)!!.deletedAt shouldBe null
                }
            }
        }

        // ── FTS reindex ────────────────────────────────────────────────────────

        test("mergeSeries reindexes book_search.series_names for affected books") {
            withSqlDatabase {
                val dbs = this
                sql.seedTestLibraryAndFolder()
                val deps = makeMergeSeriesServiceAndDeps(dbs)
                runTest {
                    val sourceId = deps.seriesRepo.resolveOrCreate("Source Series")
                    val targetId = deps.seriesRepo.resolveOrCreate("Target Series")
                    deps.bookRepo.upsert(bookFixtureForSeriesMerge("b1", "Book One", sourceId))

                    deps.service.mergeSeries(sourceId, targetId).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // After merge, FTS should find book via target's name.
                    ftsSeriesNamesMatchForBook(dbs, bookId = "b1", searchTerm = "Target") shouldBe true
                    // And should NOT find it via source's name any more.
                    ftsSeriesNamesMatchForBook(dbs, bookId = "b1", searchTerm = "Source") shouldBe false
                }
            }
        }
    })

// ── Test fixtures and helpers ──────────────────────────────────────────────────

private data class MergeSeriesServiceDeps(
    val service: SeriesServiceImpl,
    val seriesRepo: SeriesRepository,
    val bookRepo: BookRepository,
)

private fun makeMergeSeriesServiceAndDeps(dbs: SqlTestDatabases): MergeSeriesServiceDeps {
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
            reindexer = reindexer,
            sqlDb = dbs.sql,
            accessPolicy = BookAccessPolicy(dbs.sql, dbs.driver),
            principal = rootPrincipal(),
        )
    return MergeSeriesServiceDeps(service, seriesRepo, bookRepo)
}

/** Minimal [BookSyncPayload] linked to a single series. */
private fun bookFixtureForSeriesMerge(
    id: String,
    title: String,
    seriesId: SeriesId,
    sequence: String = "1",
    rootRelPath: String = "books/$id",
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
                    name = "Placeholder Name",
                    sequence = sequence,
                ),
            ),
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
                BookChapterPayload(id = "ch-$id", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

/** Distinct book IDs currently linked to [seriesId], sorted for stable assertion. */
private suspend fun bookIdsForSeriesInTest(
    dbs: SqlTestDatabases,
    seriesId: String,
): List<String> =
    withContext(Dispatchers.IO) {
        dbs.sql.bookSeriesMembershipsQueries
            .bookIdsForSeries(seriesId)
            .executeAsList()
    }.sorted()

/**
 * Returns true if a column-scoped MATCH on `book_search.series_names` for [searchTerm]
 * finds the FTS row mapped to [bookId] via `book_search_map`.
 */
private suspend fun ftsSeriesNamesMatchForBook(
    dbs: SqlTestDatabases,
    bookId: String,
    searchTerm: String,
): Boolean {
    val dq = '"'
    val quotedTerm = "$dq${searchTerm.replace("$dq", "$dq$dq")}$dq"
    return withContext(Dispatchers.IO) {
        dbs.driver
            .executeQuery(
                identifier = null,
                sql =
                    "SELECT bs.rowid FROM book_search bs " +
                        "JOIN book_search_map m ON m.rowid = bs.rowid " +
                        "WHERE bs.series_names MATCH ? AND m.book_id = ?",
                mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                parameters = 2,
                binders = {
                    bindString(0, quotedTerm)
                    bindString(1, bookId)
                },
            ).value
    }
}
