@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.QueryResult
import com.calypsan.listenup.api.error.ContributorError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Integration tests for [ContributorServiceImpl.mergeContributors] (Books-C2 Task 14).
 *
 * Verifies the full transactional cascade — junction relink with `creditedAs`
 * preservation, alias merge with case-insensitive dedup, source soft-delete, and
 * both FTS reindex passes (book_search.contributor_names + contributor_search.aliases).
 *
 * Modelled on the `deleteContributor` tests in [ContributorServiceImplTest] — real
 * in-memory Flyway-migrated SQLite, repositories wired to the same `Database`.
 */
class ContributorServiceImplMergeTest :
    FunSpec({

        // ── Validation failures ────────────────────────────────────────────────

        test("mergeContributors returns MergeSelfTarget when source equals target") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val id = contributorRepo.resolveOrCreate("Stephen King", sortName = null)

                    val result = service.mergeContributors(id, id)

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ContributorError.MergeSelfTarget>()
                }
            }
        }

        test("mergeContributors returns NotFound when source does not exist") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val targetId = contributorRepo.resolveOrCreate("Stephen King", sortName = null)

                    val result = service.mergeContributors(ContributorId("missing"), targetId)

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ContributorError.NotFound>()
                }
            }
        }

        test("mergeContributors returns NotFound when target does not exist") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("Richard Bachman", sortName = null)

                    val result = service.mergeContributors(sourceId, ContributorId("missing"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ContributorError.NotFound>()
                }
            }
        }

        test("mergeContributors returns NotFound when source is already tombstoned") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("Source Person", sortName = null)
                    val targetId = contributorRepo.resolveOrCreate("Target Person", sortName = null)
                    contributorRepo.softDelete(sourceId).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val result = service.mergeContributors(sourceId, targetId)

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ContributorError.NotFound>()
                }
            }
        }

        // ── Happy-path cascade ─────────────────────────────────────────────────

        test("mergeContributors relinks junctions, captures creditedAs, soft-deletes source") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("Richard Bachman", sortName = null)
                    val targetId = contributorRepo.resolveOrCreate("Stephen King", sortName = null)

                    // b1, b2: source contributor, no explicit credited_as
                    bookRepo.upsert(bookFixtureForMerge("b1", "The Long Walk", sourceId, "Richard Bachman"))
                    bookRepo.upsert(bookFixtureForMerge("b2", "Thinner", sourceId, "Richard Bachman", rootRelPath = "Bachman/Thinner"))
                    // b3: source contributor with explicit credited_as override
                    bookRepo.upsert(
                        bookFixtureForMerge(
                            "b3",
                            "Roadwork",
                            sourceId,
                            "Richard Bachman",
                            rootRelPath = "Bachman/Roadwork",
                            creditedAs = "R. Bachman",
                        ),
                    )

                    val initialB1Rev = bookRepo.findById(BookId("b1"))!!.revision
                    val initialB2Rev = bookRepo.findById(BookId("b2"))!!.revision
                    val initialB3Rev = bookRepo.findById(BookId("b3"))!!.revision

                    val result = service.mergeContributors(sourceId, targetId)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Junction relink: source has no rows; target has every book.
                    readBookIdsForContributor(db, sourceId.value).shouldBeEmpty()
                    readBookIdsForContributor(db, targetId.value) shouldContainExactlyInAnyOrder
                        listOf("b1", "b2", "b3")

                    // creditedAs preservation: b1/b2 captured source name; b3 kept its override.
                    creditedAsFor(db, "b1", targetId.value) shouldBe "Richard Bachman"
                    creditedAsFor(db, "b2", targetId.value) shouldBe "Richard Bachman"
                    creditedAsFor(db, "b3", targetId.value) shouldBe "R. Bachman"

                    // Every affected book was re-upserted → revision bumped → SSE emission.
                    bookRepo.findById(BookId("b1"))!!.revision shouldNotBe initialB1Rev
                    bookRepo.findById(BookId("b2"))!!.revision shouldNotBe initialB2Rev
                    bookRepo.findById(BookId("b3"))!!.revision shouldNotBe initialB3Rev

                    // Target gained source.name as an alias.
                    val targetAfter = contributorRepo.findById(targetId.value).shouldNotBeNull()
                    targetAfter.aliases shouldContainExactlyInAnyOrder listOf("Richard Bachman")

                    // Source is soft-deleted.
                    val sourceAfter = contributorRepo.findById(sourceId.value).shouldNotBeNull()
                    sourceAfter.deletedAt shouldNotBe null
                }
            }
        }

        // ── Alias merge semantics ──────────────────────────────────────────────

        test("mergeContributors dedups aliases case-insensitively, preserving original case") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("Richard Bachman", sortName = null)
                    val targetId = contributorRepo.resolveOrCreate("Stephen King", sortName = null)
                    // Pre-seed target with a lowercased alias that should collide with source.name
                    val targetPayload = contributorRepo.findById(targetId.value)!!
                    contributorRepo
                        .upsert(targetPayload.copy(aliases = listOf("richard bachman")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val result = service.mergeContributors(sourceId, targetId)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val targetAfter = contributorRepo.findById(targetId.value).shouldNotBeNull()
                    // Exactly one alias survives the dedup — original lowercased form is preserved.
                    targetAfter.aliases shouldHaveSize 1
                    targetAfter.aliases[0] shouldBe "richard bachman"
                }
            }
        }

        test("mergeContributors excludes target's own name from the alias set") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val targetId = contributorRepo.resolveOrCreate("Stephen King", sortName = null)
                    // Source's aliases include "Stephen King" (matches target.name case-insensitively).
                    // After merge, target must NOT gain itself as an alias.
                    val sourceId = contributorRepo.resolveOrCreate("Richard Bachman", sortName = null)
                    val sourcePayload = contributorRepo.findById(sourceId.value)!!
                    contributorRepo
                        .upsert(
                            sourcePayload.copy(aliases = listOf("stephen king", "Maddrax")),
                        ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val result = service.mergeContributors(sourceId, targetId)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val targetAfter = contributorRepo.findById(targetId.value).shouldNotBeNull()
                    // Target gained source.name and the non-self-matching source alias only.
                    targetAfter.aliases shouldContainExactlyInAnyOrder listOf("Richard Bachman", "Maddrax")
                }
            }
        }

        test("mergeContributors carries source's pre-existing aliases into target's alias set") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("Source Person", sortName = null)
                    val sourcePayload = contributorRepo.findById(sourceId.value)!!
                    contributorRepo
                        .upsert(sourcePayload.copy(aliases = listOf("Source Alias 1", "Source Alias 2")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val targetId = contributorRepo.resolveOrCreate("Target Person", sortName = null)

                    val result = service.mergeContributors(sourceId, targetId)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val targetAfter = contributorRepo.findById(targetId.value).shouldNotBeNull()
                    targetAfter.aliases shouldContainExactlyInAnyOrder
                        listOf("Source Person", "Source Alias 1", "Source Alias 2")
                }
            }
        }

        // ── FTS reindex ────────────────────────────────────────────────────────

        test("mergeContributors reindexes book_search.contributor_names for affected books") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("Richard Bachman", sortName = null)
                    val targetId = contributorRepo.resolveOrCreate("Stephen King", sortName = null)
                    bookRepo.upsert(bookFixtureForMerge("b1", "The Long Walk", sourceId, "Richard Bachman"))

                    service
                        .mergeContributors(sourceId, targetId)
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // book_search.contributor_names for b1 must now MATCH the target's canonical name.
                    ftsBookContributorMatch(db, "b1", "Stephen King") shouldBe true
                }
            }
        }

        test("mergeContributors reindexes contributor_search.aliases for the target") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("Richard Bachman", sortName = null)
                    val targetId = contributorRepo.resolveOrCreate("Stephen King", sortName = null)

                    service
                        .mergeContributors(sourceId, targetId)
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // contributor_search.aliases for target must now MATCH "Bachman".
                    ftsAliasesMatch(db, targetId.value, "Bachman") shouldBe true
                }
            }
        }
    })

// ── Test fixtures and helpers ──────────────────────────────────────────────────

private data class MergeServiceDeps(
    val service: ContributorServiceImpl,
    val contributorRepo: ContributorRepository,
    val bookRepo: BookRepository,
    val reindexer: BookSearchReindexer,
)

private fun makeServiceAndDeps(db: SqlTestDatabases): MergeServiceDeps {
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
    return MergeServiceDeps(service, contributorRepo, bookRepo, reindexer)
}

/**
 * Builds a [BookSyncPayload] linking a single contributor — optionally with an explicit
 * `credited_as` override. Used by merge tests that need to distinguish the
 * "captured-from-NULL" case from the "preserved-existing-value" case.
 */
private fun bookFixtureForMerge(
    id: String,
    title: String,
    contributorId: ContributorId,
    contributorName: String,
    rootRelPath: String = "books/$id",
    creditedAs: String? = null,
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
                    name = contributorName,
                    sortName = null,
                    role = "author",
                    creditedAs = creditedAs,
                ),
            ),
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
                BookChapterPayload(id = "ch-$id", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

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

/**
 * Reads the `credited_as` column for the junction row joining [bookId] and [contributorId].
 * Returns null when the column is NULL or the row does not exist.
 */
private suspend fun creditedAsFor(
    db: SqlTestDatabases,
    bookId: String,
    contributorId: String,
): String? =
    withContext(Dispatchers.IO) {
        db.sql.bookContributorsQueries
            .selectCreditedAs(book_id = bookId, contributor_id = contributorId)
            .executeAsOneOrNull()
            ?.credited_as
    }

/**
 * Returns true if a MATCH on `book_search.contributor_names` for [searchTerm] finds
 * the FTS row for [bookId] (resolved via `book_search_map`). Column-scoped so it
 * doesn't false-positive on cross-column hits (title, series, etc.).
 */
private suspend fun ftsBookContributorMatch(
    db: SqlTestDatabases,
    bookId: String,
    searchTerm: String,
): Boolean {
    val dq = '"'
    val quotedTerm = "$dq${searchTerm.replace("$dq", "$dq$dq")}$dq"
    return withContext(Dispatchers.IO) {
        db.driver
            .executeQuery(
                identifier = null,
                sql =
                    "SELECT bs.rowid FROM book_search bs " +
                        "JOIN book_search_map m ON m.rowid = bs.rowid " +
                        "WHERE bs.contributor_names MATCH ? AND m.book_id = ?",
                mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                parameters = 2,
                binders = {
                    bindString(0, quotedTerm)
                    bindString(1, bookId)
                },
            ).value
    }
}

/**
 * Returns true if a MATCH on `contributor_search.aliases` for [searchTerm] finds
 * the FTS row for [contributorId]. Column-scoped to the `aliases` column only —
 * never matches on name/sort_name/description.
 */
private suspend fun ftsAliasesMatch(
    db: SqlTestDatabases,
    contributorId: String,
    searchTerm: String,
): Boolean {
    val dq = '"'
    val quotedTerm = "$dq${searchTerm.replace("$dq", "$dq$dq")}$dq"
    return withContext(Dispatchers.IO) {
        db.driver
            .executeQuery(
                identifier = null,
                sql =
                    "SELECT c.id FROM contributor_search cs " +
                        "JOIN contributors c ON c.rowid = cs.rowid " +
                        "WHERE cs.aliases MATCH ? AND c.id = ?",
                mapper = { cursor -> QueryResult.Value(cursor.next().value) },
                parameters = 2,
                binders = {
                    bindString(0, quotedTerm)
                    bindString(1, contributorId)
                },
            ).value
    }
}
