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
import com.calypsan.listenup.server.db.sqldelight.Contributors
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
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

                    // The merge records NO alias — aliases are user-curated facts only;
                    // merge durability lives in the merged_into redirect.
                    val targetAfter = contributorRepo.findById(targetId.value).shouldNotBeNull()
                    targetAfter.aliases.shouldBeEmpty()

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
                    // Target gained only the non-self-matching pre-existing source alias —
                    // never source's own name.
                    targetAfter.aliases shouldContainExactlyInAnyOrder listOf("Maddrax")
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
                    // Only the source's pre-existing (user-curated) aliases transfer —
                    // its name is never manufactured into one.
                    targetAfter.aliases shouldContainExactlyInAnyOrder
                        listOf("Source Alias 1", "Source Alias 2")
                }
            }
        }

        // ── Alias hygiene: source's name never becomes an alias ────────────────

        test("mergeContributors does not alias source's name when it's a punctuation variant of target's") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("George R. R. Martin", sortName = null)
                    val targetId = contributorRepo.resolveOrCreate("George R.R. Martin", sortName = null)

                    val result = service.mergeContributors(sourceId, targetId)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Same normalized name as target — recording it as an alias would be noise.
                    val targetAfter = contributorRepo.findById(targetId.value).shouldNotBeNull()
                    targetAfter.aliases.shouldBeEmpty()
                }
            }
        }

        test("mergeContributors does not alias source's name even when it's not a punctuation variant") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    // NOT a punctuation/spacing variant — the reported bug: this merge used to
                    // manufacture a "Richard C. Schwartz PHD" alias on the target.
                    val sourceId = contributorRepo.resolveOrCreate("Richard C. Schwartz PHD", sortName = null)
                    val targetId = contributorRepo.resolveOrCreate("Richard C. Schwartz", sortName = null)

                    val result = service.mergeContributors(sourceId, targetId)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val targetAfter = contributorRepo.findById(targetId.value).shouldNotBeNull()
                    targetAfter.aliases.shouldBeEmpty()
                }
            }
        }

        // ── merged_into redirect ───────────────────────────────────────────────

        test("mergeContributors records merged_into on the tombstoned source row") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("Richard C. Schwartz PHD", sortName = null)
                    val targetId = contributorRepo.resolveOrCreate("Richard C. Schwartz", sortName = null)

                    val result = service.mergeContributors(sourceId, targetId)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // The loser row carries both the tombstone and the redirect — the durable,
                    // server-only mapping the scanner's resolve follows across rescans.
                    val sourceRow = contributorRow(db, sourceId.value)
                    sourceRow.merged_into shouldBe targetId.value
                    sourceRow.deleted_at shouldNotBe null
                }
            }
        }

        test("mergeContributors still aliases a source's genuine pre-existing alias despite a punctuation-variant name") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("George R. R. Martin", sortName = null)
                    val sourcePayload = contributorRepo.findById(sourceId.value)!!
                    contributorRepo
                        .upsert(sourcePayload.copy(aliases = listOf("GRRM")))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val targetId = contributorRepo.resolveOrCreate("George R.R. Martin", sortName = null)

                    val result = service.mergeContributors(sourceId, targetId)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val targetAfter = contributorRepo.findById(targetId.value).shouldNotBeNull()
                    // The punctuation-variant name itself is skipped, but the genuine alias survives.
                    targetAfter.aliases shouldContainExactlyInAnyOrder listOf("GRRM")
                }
            }
        }

        // ── Same-book collision (composite PK (book_id, contributor_id, role)) ─

        test("mergeContributors dedupes when a book credits both source and target in the same role") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("George R. R. Martin", sortName = null)
                    val targetId = contributorRepo.resolveOrCreate("George R.R. Martin", sortName = null)

                    // b1 credits BOTH spellings as author — the duplicate-spelling collision case.
                    bookRepo.upsert(
                        bookFixtureWithContributors(
                            "b1",
                            "A Game of Thrones",
                            listOf(
                                BookContributorPayload(
                                    id = sourceId.value,
                                    name = "George R. R. Martin",
                                    sortName = null,
                                    role = "author",
                                    creditedAs = null,
                                ),
                                BookContributorPayload(
                                    id = targetId.value,
                                    name = "George R.R. Martin",
                                    sortName = null,
                                    role = "author",
                                    creditedAs = null,
                                ),
                            ),
                        ),
                    )

                    val result = service.mergeContributors(sourceId, targetId)

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    readBookIdsForContributor(db, sourceId.value).shouldBeEmpty()
                    bookContributorRowCount(db, "b1", targetId.value, "author") shouldBe 1
                }
            }
        }

        test("mergeContributors preserves both credits when source and target hold different roles on the same book") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val deps = makeServiceAndDeps(db)
                val service = deps.service
                val contributorRepo = deps.contributorRepo
                val bookRepo = deps.bookRepo
                runTest {
                    val sourceId = contributorRepo.resolveOrCreate("Stephen King", sortName = null)
                    val targetId = contributorRepo.resolveOrCreate("Narrator King", sortName = null)

                    // b1: source credited as author, target credited as narrator — no PK collision.
                    bookRepo.upsert(
                        bookFixtureWithContributors(
                            "b1",
                            "It",
                            listOf(
                                BookContributorPayload(
                                    id = sourceId.value,
                                    name = "Stephen King",
                                    sortName = null,
                                    role = "author",
                                    creditedAs = null,
                                ),
                                BookContributorPayload(
                                    id = targetId.value,
                                    name = "Narrator King",
                                    sortName = null,
                                    role = "narrator",
                                    creditedAs = null,
                                ),
                            ),
                        ),
                    )

                    val result = service.mergeContributors(sourceId, targetId)

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    // Both roles survive on target — the relinked author credit and the
                    // pre-existing narrator credit.
                    bookContributorRowCount(db, "b1", targetId.value, "author") shouldBe 1
                    bookContributorRowCount(db, "b1", targetId.value, "narrator") shouldBe 1
                    creditedAsForRole(db, "b1", targetId.value, "author") shouldBe "Stephen King"
                }
            }
        }

        test("mergeContributors dedupes a colliding book while preserving creditedAs on a non-colliding book") {
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

                    // Book A: source and target both credited as author on the same book — collides.
                    bookRepo.upsert(
                        bookFixtureWithContributors(
                            "book-a",
                            "The Long Walk",
                            listOf(
                                BookContributorPayload(
                                    id = sourceId.value,
                                    name = "Richard Bachman",
                                    sortName = null,
                                    role = "author",
                                    creditedAs = null,
                                ),
                                BookContributorPayload(
                                    id = targetId.value,
                                    name = "Stephen King",
                                    sortName = null,
                                    role = "author",
                                    creditedAs = null,
                                ),
                            ),
                            rootRelPath = "Bachman/TheLongWalk",
                        ),
                    )
                    // Book B: only source credited — relinks normally, no collision.
                    bookRepo.upsert(
                        bookFixtureForMerge("book-b", "Thinner", sourceId, "Richard Bachman", rootRelPath = "Bachman/Thinner"),
                    )

                    val result = service.mergeContributors(sourceId, targetId)

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                    readBookIdsForContributor(db, sourceId.value).shouldBeEmpty()
                    // Book A deduplicated to a single target row.
                    bookContributorRowCount(db, "book-a", targetId.value, "author") shouldBe 1
                    // Book B relinked normally, creditedAs captured from the source's name.
                    bookContributorRowCount(db, "book-b", targetId.value, "author") shouldBe 1
                    creditedAsFor(db, "book-b", targetId.value) shouldBe "Richard Bachman"
                }
            }
        }

        // ── FTS reindex ────────────────────────────────────────────────────────
    })

// ── Test fixtures and helpers ──────────────────────────────────────────────────

private data class MergeServiceDeps(
    val service: ContributorServiceImpl,
    val contributorRepo: ContributorRepository,
    val bookRepo: BookRepository,
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
    val service =
        ContributorServiceImpl(
            contributorRepo = contributorRepo,
            bookRepo = bookRepo,
            sqlDb = db.sql,
            accessPolicy = BookAccessPolicy(db.sql, db.driver),
            principal = rootPrincipal(),
        )
    return MergeServiceDeps(service, contributorRepo, bookRepo)
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

/**
 * Builds a [BookSyncPayload] with an arbitrary set of contributor credits — used by the
 * same-book collision tests, which need more than one contributor row per book.
 */
private fun bookFixtureWithContributors(
    id: String,
    title: String,
    contributors: List<BookContributorPayload>,
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
        contributors = contributors,
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

/** Count of `book_contributors` rows for (bookId, contributorId, role) — expected 0 or 1. */
private suspend fun bookContributorRowCount(
    db: SqlTestDatabases,
    bookId: String,
    contributorId: String,
    role: String,
): Int =
    withContext(Dispatchers.IO) {
        db.sql.bookContributorsQueries
            .selectByBookIds(listOf(bookId))
            .executeAsList()
            .count { it.contributor_id == contributorId && it.role == role }
    }

/**
 * Reads the `credited_as` column for the (bookId, contributorId, role) junction row —
 * role-scoped, unlike [creditedAsFor], so it stays correct when a contributor holds more
 * than one role on the same book (e.g. author + narrator after a merge).
 */
private suspend fun creditedAsForRole(
    db: SqlTestDatabases,
    bookId: String,
    contributorId: String,
    role: String,
): String? =
    withContext(Dispatchers.IO) {
        db.sql.bookContributorsQueries
            .selectByBookIds(listOf(bookId))
            .executeAsList()
            .firstOrNull { it.contributor_id == contributorId && it.role == role }
            ?.credited_as
    }

/** Raw `contributors` row for [id] — for asserting server-only columns like `merged_into`. */
private suspend fun contributorRow(
    db: SqlTestDatabases,
    id: String,
): Contributors =
    withContext(Dispatchers.IO) {
        db.sql.contributorsQueries
            .selectById(id)
            .executeAsOne()
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
