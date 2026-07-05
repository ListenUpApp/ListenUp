@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.QueryResult
import com.calypsan.listenup.api.dto.ContributorUpdate
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
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
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
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

class ContributorServiceImplTest :
    FunSpec({

        test("getContributor returns Success with the payload for an existing contributor") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val id = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)

                    val result = service.getContributor(id)

                    val success = result.shouldBeInstanceOf<AppResult.Success<ContributorSyncPayload?>>()
                    success.data shouldNotBe null
                    success.data!!.id shouldBe id.value
                    success.data!!.name shouldBe "Brandon Sanderson"
                }
            }
        }

        test("getContributor returns AppResult.Success(null) for a non-existent contributor id") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val service = makeServiceAndDeps(db).service
                runTest {
                    val result = service.getContributor(ContributorId("does-not-exist"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<ContributorSyncPayload?>>()
                    success.data shouldBe null
                }
            }
        }

        test("listBooksByContributor returns all books linked to the contributor") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val contributorId = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    bookRepo.upsert(bookFixtureWithContributor("b1", "The Way of Kings", contributorId))
                    bookRepo.upsert(bookFixtureWithContributor("b2", "Words of Radiance", contributorId, rootRelPath = "WoR"))

                    val result = service.listBooksByContributor(contributorId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data shouldHaveSize 2
                }
            }
        }

        test("listBooksByContributor returns empty list when contributor has no books") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val contributorId = contributorRepo.resolveOrCreate("Unknown Author", sortName = null)

                    val result = service.listBooksByContributor(contributorId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data.shouldBeEmpty()
                }
            }
        }

        // ── updateContributor ──────────────────────────────────────────────────

        test("updateContributor applies the name patch when the contributor exists") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val id = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)

                    val result = service.updateContributor(id, ContributorUpdate(name = "B. Sanderson"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val reread = contributorRepo.findById(id.value)
                    reread.shouldNotBeNull()
                    reread.name shouldBe "B. Sanderson"
                }
            }
        }

        test("updateContributor triggers FTS reindex for all linked books when the name changes") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val contributorId = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    bookRepo.upsert(bookFixtureWithContributor("b1", "The Way of Kings", contributorId))
                    bookRepo.upsert(bookFixtureWithContributor("b2", "Words of Radiance", contributorId, rootRelPath = "WoR"))
                    val rowidB1 = lookupFtsRowid(db, "b1")
                    val rowidB2 = lookupFtsRowid(db, "b2")
                    // Plant a sentinel contributor_names so the test can prove a real reindex occurred.
                    overwriteFtsContributorNames(db, rowid = rowidB1, sentinel = "SENTINELNOTOVERWRITTEN")
                    overwriteFtsContributorNames(db, rowid = rowidB2, sentinel = "SENTINELNOTOVERWRITTEN")

                    val result = service.updateContributor(contributorId, ContributorUpdate(name = "B. Sanderson"))

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    // Both FTS rows should match the new contributor name (sentinel was overwritten by reindex).
                    ftsContributorNamesMatch(db, rowidB1, "B. Sanderson") shouldBe true
                    ftsContributorNamesMatch(db, rowidB2, "B. Sanderson") shouldBe true
                    ftsContributorNamesMatch(db, rowidB1, "SENTINELNOTOVERWRITTEN") shouldBe false
                }
            }
        }

        test("updateContributor does NOT trigger FTS reindex when only non-name fields change") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val contributorId = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    bookRepo.upsert(bookFixtureWithContributor("b1", "The Way of Kings", contributorId))
                    val rowidB1 = lookupFtsRowid(db, "b1")
                    // Tripwire: if the implementation reindexes when it shouldn't, the
                    // sentinel will be overwritten with the live contributor name.
                    overwriteFtsContributorNames(db, rowid = rowidB1, sentinel = "SENTINELNOTOVERWRITTEN")

                    val result =
                        service.updateContributor(
                            contributorId,
                            ContributorUpdate(description = "Fantasy author"),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    // Sentinel must still match — reindex was skipped.
                    ftsContributorNamesMatch(db, rowidB1, "SENTINELNOTOVERWRITTEN") shouldBe true
                    ftsContributorNamesMatch(db, rowidB1, "Brandon Sanderson") shouldBe false
                    // Description was applied.
                    val reread = contributorRepo.findById(contributorId.value)
                    reread.shouldNotBeNull()
                    reread.description shouldBe "Fantasy author"
                }
            }
        }

        test("updateContributor returns ContributorError.NotFound when the contributor does not exist") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val service = makeServiceAndDeps(db).service
                runTest {
                    val result =
                        service.updateContributor(ContributorId("ghost"), ContributorUpdate(name = "Anyone"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ContributorError.NotFound>()
                }
            }
        }

        // ── deleteContributor ──────────────────────────────────────────────────

        test("deleteContributor cascades — drops junctions, re-upserts affected books, soft-deletes the contributor") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val targetId = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    val coauthorId = contributorRepo.resolveOrCreate("Janci Patterson", sortName = null)
                    // b1 has both contributors; b2 has only the target.
                    bookRepo.upsert(bookFixtureWithTwoContributors("b1", "The Way of Kings", targetId, coauthorId))
                    bookRepo.upsert(bookFixtureWithContributor("b2", "Words of Radiance", targetId, rootRelPath = "WoR"))
                    val initialB1 =
                        bookRepo.findById(
                            com.calypsan.listenup.core
                                .BookId("b1"),
                        )!!
                    val initialB2 =
                        bookRepo.findById(
                            com.calypsan.listenup.core
                                .BookId("b2"),
                        )!!

                    val result = service.deleteContributor(targetId)

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Junction rows for the deleted contributor are gone.
                    val remainingBookIds = readBookIdsForContributor(db, targetId.value)
                    remainingBookIds.shouldBeEmpty()

                    // b1 was re-upserted: revision bumped, target contributor stripped, coauthor preserved.
                    val updatedB1 =
                        bookRepo.findById(
                            com.calypsan.listenup.core
                                .BookId("b1"),
                        )!!
                    updatedB1.revision shouldNotBe initialB1.revision
                    updatedB1.contributors.map { it.id } shouldBe listOf(coauthorId.value)

                    // b2 was re-upserted: revision bumped, contributor list empty.
                    val updatedB2 =
                        bookRepo.findById(
                            com.calypsan.listenup.core
                                .BookId("b2"),
                        )!!
                    updatedB2.revision shouldNotBe initialB2.revision
                    updatedB2.contributors.shouldBeEmpty()

                    // Contributor is soft-deleted (deletedAt is set; getContributor still surfaces it
                    // for read-after-delete since findById bypasses the tombstone filter).
                    val tombstone = contributorRepo.findById(targetId.value)
                    tombstone.shouldNotBeNull()
                    tombstone.deletedAt shouldNotBe null
                }
            }
        }

        test("deleteContributor succeeds when the contributor has no linked books") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val targetId = contributorRepo.resolveOrCreate("Lone Wolf", sortName = null)

                    val result = service.deleteContributor(targetId)

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val tombstone = contributorRepo.findById(targetId.value)
                    tombstone.shouldNotBeNull()
                    tombstone.deletedAt shouldNotBe null
                }
            }
        }

        test("deleteContributor returns ContributorError.NotFound when the contributor does not exist") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val service = makeServiceAndDeps(db).service
                runTest {
                    val result = service.deleteContributor(ContributorId("ghost"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ContributorError.NotFound>()
                }
            }
        }
    })

private data class ServiceDeps(
    val service: ContributorServiceImpl,
    val contributorRepo: ContributorRepository,
    val bookRepo: BookRepository,
    val reindexer: BookSearchReindexer,
)

private fun makeServiceAndDeps(db: SqlTestDatabases): ServiceDeps {
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(db = db.sql, bus = bus, registry = syncRegistry)
    val seriesRepo = SeriesRepository(db.sql, bus, syncRegistry)
    val bookRepo =
        BookRepository(
            db = db.sql,
            driver = db.driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = GenreRepository(db.sql, bus, syncRegistry),
        )
    val tagRepo = TagRepository(db = db.sql, bus = bus, registry = syncRegistry)
    val bookTagRepo = BookTagRepository(db = db.sql, bus = bus, registry = syncRegistry)
    val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, db.sql, db.driver)
    val service =
        ContributorServiceImpl(
            contributorRepo = contributorRepo,
            bookRepo = bookRepo,
            reindexer = reindexer,
            sqlDb = db.sql,
            accessPolicy = BookAccessPolicy(db.sql, db.driver),
            principal = rootPrincipal(),
        )
    return ServiceDeps(service, contributorRepo, bookRepo, reindexer)
}

/**
 * Reads the FTS rowid that [BookRepository.upsert] allocated for [bookId]
 * via `book_search_map`. Books-C1 tests need this to address the FTS row
 * created automatically by the books pipeline.
 */
private suspend fun lookupFtsRowid(
    db: SqlTestDatabases,
    bookId: String,
): Int {
    val rowid =
        withContext(Dispatchers.IO) {
            db.driver
                .executeQuery(
                    identifier = null,
                    sql = "SELECT rowid FROM book_search_map WHERE book_id = ?",
                    mapper = { cursor ->
                        QueryResult.Value(if (cursor.next().value) cursor.getLong(0)?.toInt() else null)
                    },
                    parameters = 1,
                    binders = { bindString(0, bookId) },
                ).value
        }
    check(rowid != null && rowid > 0) { "No book_search_map row found for bookId=$bookId" }
    return rowid
}

/**
 * Replaces the `contributor_names` cell of the FTS row at [rowid] with a sentinel
 * value. Acts as a tripwire so tests can prove whether a real reindex re-read
 * the source tables (overwriting the sentinel) or skipped (sentinel survives).
 *
 * `book_search` is contentless_delete=1, so the only safe mutation idiom is
 * DELETE + re-INSERT of the entire row.
 */
private suspend fun overwriteFtsContributorNames(
    db: SqlTestDatabases,
    rowid: Int,
    sentinel: String,
) {
    withContext(Dispatchers.IO) {
        db.driver.execute(null, "DELETE FROM book_search WHERE rowid = $rowid", 0)
        db.driver.execute(
            identifier = null,
            sql =
                "INSERT INTO book_search(rowid, title, subtitle, description, contributor_names, series_names, tags) " +
                    "VALUES ($rowid, ?, '', '', ?, '', '')",
            parameters = 2,
            binders = {
                bindString(0, "Test Book b$rowid")
                bindString(1, sentinel)
            },
        )
    }
}

/**
 * Returns true if a MATCH on `contributor_names` for [searchTerm] finds [rowid].
 *
 * Uses a column-specific MATCH so the assertion is scoped to contributor_names
 * only — not a cross-column hit.
 */
private suspend fun ftsContributorNamesMatch(
    db: SqlTestDatabases,
    rowid: Int,
    searchTerm: String,
): Boolean {
    val dq = '"'
    val quotedTerm = "$dq${searchTerm.replace("$dq", "$dq$dq")}$dq"
    return withContext(Dispatchers.IO) {
        db.driver
            .executeQuery(
                identifier = null,
                sql = "SELECT rowid FROM book_search WHERE contributor_names MATCH ? AND rowid = ?",
                mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                parameters = 2,
                binders = {
                    bindString(0, quotedTerm)
                    bindLong(1, rowid.toLong())
                },
            ).value
    }
}

/** Distinct book IDs currently linked to [contributorId] via any junction row. */
private suspend fun readBookIdsForContributor(
    db: SqlTestDatabases,
    contributorId: String,
): List<String> =
    withContext(Dispatchers.IO) {
        db.sql.bookContributorsQueries
            .bookIdsForContributor(contributor_id = contributorId)
            .executeAsList()
    }

private fun bookFixtureWithContributor(
    id: String,
    title: String,
    contributorId: ContributorId,
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
        contributors =
            listOf(
                BookContributorPayload(
                    id = contributorId.value,
                    name = "Brandon Sanderson",
                    sortName = null,
                    role = "author",
                    creditedAs = null,
                ),
            ),
        series = emptyList(),
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

private fun bookFixtureWithTwoContributors(
    id: String,
    title: String,
    firstId: ContributorId,
    secondId: ContributorId,
): BookSyncPayload =
    bookFixtureWithContributor(id, title, firstId).copy(
        contributors =
            listOf(
                BookContributorPayload(
                    id = firstId.value,
                    name = "Brandon Sanderson",
                    sortName = null,
                    role = "author",
                    creditedAs = null,
                ),
                BookContributorPayload(
                    id = secondId.value,
                    name = "Janci Patterson",
                    sortName = null,
                    role = "author",
                    creditedAs = null,
                ),
            ),
    )
