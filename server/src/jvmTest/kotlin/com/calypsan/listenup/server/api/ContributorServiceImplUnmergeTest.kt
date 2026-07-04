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
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Integration tests for [ContributorServiceImpl.unmergeContributor] (Books-C2 Task 15).
 *
 * Verifies the inverse-of-merge cascade — alias-name split into a fresh contributor,
 * junction relink for rows matching `(contributor_id = target AND credited_as = aliasName)`
 * with `credited_as` cleared, per-affected-book re-upsert (revision bump + book.Updated),
 * alias removal from target, and both FTS reindex passes (target + new contributor
 * `book_search.contributor_names`, target `contributor_search.aliases`).
 *
 * Modelled on [ContributorServiceImplMergeTest] — real in-memory Flyway-migrated SQLite,
 * repositories wired to the same `Database`.
 */
class ContributorServiceImplUnmergeTest :
    FunSpec({

        // ── Validation failures ────────────────────────────────────────────────

        test("unmergeContributor returns NotFound when contributor does not exist") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeUnmergeServiceAndDeps(db)
                val service = deps.service
                runTest {
                    val result = service.unmergeContributor(ContributorId("missing"), "Some Alias")

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ContributorError.NotFound>()
                }
            }
        }

        test("unmergeContributor returns AliasNotFound when alias is not on the contributor") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeUnmergeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val targetId = contributorRepo.resolveOrCreate("Stephen King", sortName = null)
                    val targetPayload = contributorRepo.findById(targetId.value)!!
                    contributorRepo
                        .upsert(targetPayload.copy(aliases = listOf("Richard Bachman")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val result = service.unmergeContributor(targetId, "Some Other Alias")

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    failure.error.shouldBeInstanceOf<ContributorError.AliasNotFound>()
                }
            }
        }

        // ── Happy-path cascade ─────────────────────────────────────────────────

        test("unmergeContributor splits alias into new contributor, relinks books, clears creditedAs") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeUnmergeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val targetId = contributorRepo.resolveOrCreate("Stephen King", sortName = null)
                    val targetPayload = contributorRepo.findById(targetId.value)!!
                    contributorRepo
                        .upsert(targetPayload.copy(aliases = listOf("Richard Bachman")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Two books credited as the alias — these should be relinked to the new contributor.
                    bookRepo.upsert(
                        bookFixtureForUnmerge("b1", "The Long Walk", targetId, "Stephen King", "Richard Bachman"),
                    )
                    bookRepo.upsert(
                        bookFixtureForUnmerge(
                            "b2",
                            "Thinner",
                            targetId,
                            "Stephen King",
                            "Richard Bachman",
                            rootRelPath = "Bachman/Thinner",
                        ),
                    )
                    // One book credited under target's canonical name (no alias override) — stays with target.
                    bookRepo.upsert(
                        bookFixtureForUnmerge(
                            "b3",
                            "It",
                            targetId,
                            "Stephen King",
                            creditedAs = null,
                            rootRelPath = "King/It",
                        ),
                    )

                    val initialB1Rev = bookRepo.findById(BookId("b1"))!!.revision
                    val initialB2Rev = bookRepo.findById(BookId("b2"))!!.revision

                    val result = service.unmergeContributor(targetId, "Richard Bachman")

                    val success = result.shouldBeInstanceOf<AppResult.Success<ContributorId>>()
                    val newId = success.data
                    newId.value shouldNotBe targetId.value

                    // New contributor exists with name = aliasName, empty aliases, no enrichment.
                    val newContributor = contributorRepo.findById(newId.value).shouldNotBeNull()
                    newContributor.name shouldBe "Richard Bachman"
                    newContributor.sortName shouldBe "Richard Bachman"
                    newContributor.aliases.shouldBeEmpty()
                    newContributor.deletedAt.shouldBeNull()
                    newContributor.asin.shouldBeNull()
                    newContributor.description.shouldBeNull()
                    newContributor.imagePath.shouldBeNull()
                    newContributor.birthDate.shouldBeNull()
                    newContributor.deathDate.shouldBeNull()
                    newContributor.website.shouldBeNull()

                    // Matching books relinked to newId; credited_as cleared on the new junction rows.
                    readBookIdsForContributorUnmerge(db, targetId.value) shouldContainExactlyInAnyOrder listOf("b3")
                    readBookIdsForContributorUnmerge(db, newId.value) shouldContainExactlyInAnyOrder listOf("b1", "b2")
                    creditedAsForUnmerge(db, "b1", newId.value).shouldBeNull()
                    creditedAsForUnmerge(db, "b2", newId.value).shouldBeNull()

                    // Affected books were re-upserted → revision bumped → SSE emission.
                    bookRepo.findById(BookId("b1"))!!.revision shouldNotBe initialB1Rev
                    bookRepo.findById(BookId("b2"))!!.revision shouldNotBe initialB2Rev

                    // Alias removed from target.
                    val targetAfter = contributorRepo.findById(targetId.value).shouldNotBeNull()
                    targetAfter.aliases.shouldNotContain("Richard Bachman")
                }
            }
        }

        // ── Edge case: zero matching books ─────────────────────────────────────

        test("unmergeContributor creates empty contributor + removes alias when no books match") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeUnmergeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val targetId = contributorRepo.resolveOrCreate("Stephen King", sortName = null)
                    val targetPayload = contributorRepo.findById(targetId.value)!!
                    contributorRepo
                        .upsert(targetPayload.copy(aliases = listOf("Pseudo Bachman", "Other Alias")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    // No book_contributors rows credited as "Pseudo Bachman"

                    val result = service.unmergeContributor(targetId, "Pseudo Bachman")

                    val success = result.shouldBeInstanceOf<AppResult.Success<ContributorId>>()
                    val newId = success.data

                    val newContributor = contributorRepo.findById(newId.value).shouldNotBeNull()
                    newContributor.name shouldBe "Pseudo Bachman"
                    newContributor.aliases.shouldBeEmpty()

                    val targetAfter = contributorRepo.findById(targetId.value).shouldNotBeNull()
                    targetAfter.aliases shouldHaveSize 1
                    targetAfter.aliases[0] shouldBe "Other Alias"
                }
            }
        }

        // ── FTS reindex ────────────────────────────────────────────────────────

        test("unmergeContributor reindexes book_search.contributor_names for relinked books") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeUnmergeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val targetId = contributorRepo.resolveOrCreate("Stephen King", sortName = null)
                    val targetPayload = contributorRepo.findById(targetId.value)!!
                    contributorRepo
                        .upsert(targetPayload.copy(aliases = listOf("Richard Bachman")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    bookRepo.upsert(
                        bookFixtureForUnmerge("b1", "The Long Walk", targetId, "Stephen King", "Richard Bachman"),
                    )

                    service
                        .unmergeContributor(targetId, "Richard Bachman")
                        .shouldBeInstanceOf<AppResult.Success<ContributorId>>()

                    // book_search.contributor_names for b1 must now MATCH "Bachman" (the new contributor's name).
                    ftsBookContributorMatchUnmerge(db, "b1", "Bachman") shouldBe true
                }
            }
        }

        test("unmergeContributor reindexes contributor_search.aliases for the target") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeUnmergeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                val reindexer = deps.reindexer
                runTest {
                    val targetId = contributorRepo.resolveOrCreate("Stephen King", sortName = null)
                    val targetPayload = contributorRepo.findById(targetId.value)!!
                    contributorRepo
                        .upsert(targetPayload.copy(aliases = listOf("Richard Bachman")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    // Substrate upsert doesn't auto-reindex FTS — prime the precondition
                    // explicitly so we can verify the post-state actually changed.
                    reindexer.reindexContributorAliases(targetId.value)

                    // Before unmerge: target's contributor_search.aliases MATCHes "Bachman".
                    ftsAliasesMatchUnmerge(db, targetId.value, "Bachman") shouldBe true

                    service
                        .unmergeContributor(targetId, "Richard Bachman")
                        .shouldBeInstanceOf<AppResult.Success<ContributorId>>()

                    // After unmerge: target's contributor_search.aliases no longer MATCHes "Bachman".
                    ftsAliasesMatchUnmerge(db, targetId.value, "Bachman") shouldBe false
                }
            }
        }
    })

// ── Test fixtures and helpers ──────────────────────────────────────────────────
//
// Helpers are file-local mirrors of those in ContributorServiceImplMergeTest. Kept
// in-file to match the merge test's deliberate fixture-co-location pattern; both
// will be candidates for a shared fixture file once a third merge-shaped test
// arrives.

private data class UnmergeServiceDeps(
    val service: ContributorServiceImpl,
    val contributorRepo: ContributorRepository,
    val bookRepo: BookRepository,
    val reindexer: BookSearchReindexer,
)

private fun makeUnmergeServiceAndDeps(db: SqlTestDatabases): UnmergeServiceDeps {
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
    return UnmergeServiceDeps(service, contributorRepo, bookRepo, reindexer)
}

/**
 * Builds a [BookSyncPayload] linking a single contributor with an explicit
 * `contributorName` (the wire-level display name) and an optional `creditedAs`
 * override. Used by unmerge tests to distinguish books credited under the alias
 * (which get relinked) from books credited under the canonical name (which stay).
 */
private fun bookFixtureForUnmerge(
    id: String,
    title: String,
    contributorId: ContributorId,
    contributorName: String,
    creditedAs: String? = null,
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

private suspend fun readBookIdsForContributorUnmerge(
    db: SqlTestDatabases,
    contributorId: String,
): List<String> =
    withContext(Dispatchers.IO) {
        db.sql.bookContributorsQueries
            .bookIdsForContributor(contributor_id = contributorId)
            .executeAsList()
    }

private suspend fun creditedAsForUnmerge(
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

private suspend fun ftsBookContributorMatchUnmerge(
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

private suspend fun ftsAliasesMatchUnmerge(
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
