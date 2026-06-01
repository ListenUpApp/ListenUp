@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

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
import com.calypsan.listenup.server.db.BookSeriesMembershipTable
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import com.calypsan.listenup.server.testing.rootPrincipal
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

class SeriesServiceImplTest :
    FunSpec({

        test("getSeries returns Success with the payload for an existing series") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(db)
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

        test("getSeries returns Success(null) for a non-existent series id") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val service = makeSeriesServiceAndDeps(db).service
                runTest {
                    val result = service.getSeries(SeriesId("does-not-exist"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<SeriesSyncPayload?>>()
                    success.data shouldBe null
                }
            }
        }

        test("listBooksBySeries returns all books belonging to the series in position order") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(db)
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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(db)
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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(db)
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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(db)
                val service = deps.service
                val seriesRepo = deps.seriesRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val seriesId = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    bookRepo.upsert(bookFixtureWithSeries("b1", "The Way of Kings", seriesId, "1"))
                    bookRepo.upsert(bookFixtureWithSeries("b2", "Words of Radiance", seriesId, "2", rootRelPath = "WoR"))
                    val rowidB1 = lookupFtsRowidForSeries(db, "b1")
                    val rowidB2 = lookupFtsRowidForSeries(db, "b2")
                    // Plant a sentinel series_names so the test can prove a real reindex occurred.
                    overwriteFtsSeriesNames(db, rowid = rowidB1, sentinel = "SENTINELNOTOVERWRITTEN")
                    overwriteFtsSeriesNames(db, rowid = rowidB2, sentinel = "SENTINELNOTOVERWRITTEN")

                    val result = service.updateSeries(seriesId, SeriesUpdate(name = "Stormlight"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    // Both FTS rows should match the new series name (sentinel was overwritten by reindex).
                    ftsSeriesNamesMatch(db, rowidB1, "Stormlight") shouldBe true
                    ftsSeriesNamesMatch(db, rowidB2, "Stormlight") shouldBe true
                    ftsSeriesNamesMatch(db, rowidB1, "SENTINELNOTOVERWRITTEN") shouldBe false
                }
            }
        }

        test("updateSeries does NOT trigger FTS reindex when only non-name fields change") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(db)
                val service = deps.service
                val seriesRepo = deps.seriesRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val seriesId = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    bookRepo.upsert(bookFixtureWithSeries("b1", "The Way of Kings", seriesId, "1"))
                    val rowidB1 = lookupFtsRowidForSeries(db, "b1")
                    // Tripwire: if the implementation reindexes when it shouldn't, the
                    // sentinel will be overwritten with the live series name.
                    overwriteFtsSeriesNames(db, rowid = rowidB1, sentinel = "SENTINELNOTOVERWRITTEN")

                    val result =
                        service.updateSeries(
                            seriesId,
                            SeriesUpdate(description = "Epic fantasy by Brandon Sanderson"),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    // Sentinel must still match — reindex was skipped.
                    ftsSeriesNamesMatch(db, rowidB1, "SENTINELNOTOVERWRITTEN") shouldBe true
                    ftsSeriesNamesMatch(db, rowidB1, "Stormlight") shouldBe false
                    // Description was applied.
                    val reread = seriesRepo.findById(seriesId.value)
                    reread.shouldNotBeNull()
                    reread.description shouldBe "Epic fantasy by Brandon Sanderson"
                }
            }
        }

        test("updateSeries returns SeriesError.NotFound when the series does not exist") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val service = makeSeriesServiceAndDeps(db).service
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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(db)
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
                    val remainingBookIds = readBookIdsForSeries(db, targetSeriesId.value)
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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val deps = makeSeriesServiceAndDeps(db)
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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val service = makeSeriesServiceAndDeps(db).service
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

private fun makeSeriesServiceAndDeps(db: Database): SeriesServiceDeps {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(db = db, bus = bus, registry = syncRegistry)
    val seriesRepo = SeriesRepository(db = db, bus = bus, registry = syncRegistry)
    val bookRepo =
        BookRepository(
            db = db,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
        )
    val tagRepo = TagRepository(db = db, bus = bus, registry = syncRegistry)
    val bookTagRepo = BookTagRepository(db = db, bus = bus, registry = syncRegistry)
    val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, db)
    val service =
        SeriesServiceImpl(
            seriesRepo = seriesRepo,
            bookRepo = bookRepo,
            reindexer = reindexer,
            db = db,
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
    db: Database,
    bookId: String,
): Int {
    var rowid = -1
    suspendTransaction(db) {
        val tx = TransactionManager.current()
        tx.exec(
            stmt = "SELECT rowid FROM book_search_map WHERE book_id = ?",
            args = listOf(TextColumnType() to bookId),
        ) { rs ->
            if (rs.next()) rowid = rs.getInt("rowid")
        }
    }
    check(rowid > 0) { "No book_search_map row found for bookId=$bookId" }
    return rowid
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
    db: Database,
    rowid: Int,
    sentinel: String,
) {
    suspendTransaction(db) {
        val tx = TransactionManager.current()
        tx.exec("DELETE FROM book_search WHERE rowid = $rowid")
        tx.exec(
            stmt =
                "INSERT INTO book_search(rowid, title, subtitle, description, contributor_names, series_names, tags) " +
                    "VALUES ($rowid, ?, '', '', '', ?, '')",
            args =
                listOf(
                    TextColumnType() to "Test Book b$rowid",
                    TextColumnType() to sentinel,
                ),
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
    db: Database,
    rowid: Int,
    searchTerm: String,
): Boolean {
    val dq = '"'
    val quotedTerm = "$dq${searchTerm.replace("$dq", "$dq$dq")}$dq"
    var found = false
    suspendTransaction(db) {
        val tx = TransactionManager.current()
        tx.exec(
            stmt = "SELECT rowid FROM book_search WHERE series_names MATCH ? AND rowid = ?",
            args =
                listOf(
                    TextColumnType() to quotedTerm,
                    IntegerColumnType() to rowid,
                ),
        ) { rs ->
            found = rs.next()
        }
    }
    return found
}

/** Distinct book IDs currently linked to [seriesId] via any junction row. */
private suspend fun readBookIdsForSeries(
    db: Database,
    seriesId: String,
): List<String> = suspendTransaction(db) { BookSeriesMembershipTable.bookIdsForSeries(seriesId) }

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
