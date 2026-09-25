@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.MergeUndoResult
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.MergeReceiptId
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.memberPrincipal
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Genre merge receipts and undo. Beyond the series contract, a genre undo also re-points the
 * aliases the merge moved (only those still pointing where the merge left them), refuses when a
 * live genre has taken the source's name, and re-files the source under its parent's CURRENT
 * path — or at the top level when that parent is gone.
 */
class GenreMergeUndoTest :
    FunSpec({

        // ── Recording ──────────────────────────────────────────────────────────

        test("a merge records every source link, which the target already held, and every alias") {
            withSqlDatabase {
                val f = genreFixture(sql, driver)
                sql.seedGenre("g-s", "Space Opera", "space-opera", "/space-opera")
                sql.seedGenre("g-t", "Science Fiction", "science-fiction", "/science-fiction")
                sql.bookGenresQueries.insertIfAbsent("book1", "g-s")
                sql.bookGenresQueries.insertIfAbsent("book2", "g-s")
                sql.bookGenresQueries.insertIfAbsent("book2", "g-t")
                sql.genreAliasesQueries.insert(raw_string = "space opera", genre_id = "g-s")
                runTest {
                    f.service.mergeGenres(GenreId("g-s"), GenreId("g-t")).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val receipt =
                        sql.genreMergeReceiptsQueries
                            .selectOpenForTarget("g-t")
                            .executeAsList()
                            .single()
                    receipt.book_count shouldBe 2L
                    sql.genreMergeReceiptsQueries
                        .selectRestorableBooks(receipt.id, "g-t")
                        .executeAsList()
                        .map { it.book_id to it.was_in_target } shouldBe listOf("book1" to 0L, "book2" to 1L)
                    sql.genreMergeReceiptsQueries.selectReceiptAliases(receipt.id).executeAsList() shouldBe listOf("space opera")
                }
            }
        }

        test("a refused merge records nothing") {
            withSqlDatabase {
                val f = genreFixture(sql, driver)
                sql.seedGenre("g-s", "Space Opera", "space-opera", "/space-opera")
                runTest {
                    f.service.mergeGenres(GenreId("g-s"), GenreId("g-s")).shouldBeInstanceOf<AppResult.Failure>()

                    sql.genreMergeReceiptsQueries
                        .selectOpenForTarget("g-s")
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }

        // ── Listing ────────────────────────────────────────────────────────────

        test("listMergeReceipts lists open merges into the genre, newest first") {
            withSqlDatabase {
                val f = genreFixture(sql, driver)
                sql.seedGenre("g-a", "Space Opera", "space-opera", "/space-opera")
                sql.seedGenre("g-b", "Cyberpunk", "cyberpunk", "/cyberpunk")
                sql.seedGenre("g-t", "Science Fiction", "science-fiction", "/science-fiction")
                runTest {
                    f.service.mergeGenres(GenreId("g-a"), GenreId("g-t"))
                    f.clock.instant += 5.minutes
                    f.service.mergeGenres(GenreId("g-b"), GenreId("g-t"))

                    f.service
                        .listMergeReceipts(GenreId("g-t"))
                        .shouldBeInstanceOf<AppResult.Success<List<MergeReceipt>>>()
                        .data
                        .map { it.sourceName } shouldBe listOf("Cyberpunk", "Space Opera")
                }
            }
        }

        // ── Undo ───────────────────────────────────────────────────────────────

        test("undo restores an untouched merge: the genre, its links, and its aliases") {
            withSqlDatabase {
                val f = genreFixture(sql, driver)
                sql.seedGenre("g-s", "Space Opera", "space-opera", "/space-opera")
                sql.seedGenre("g-t", "Science Fiction", "science-fiction", "/science-fiction")
                sql.bookGenresQueries.insertIfAbsent("book1", "g-s")
                sql.bookGenresQueries.insertIfAbsent("book2", "g-s")
                sql.bookGenresQueries.insertIfAbsent("book2", "g-t")
                sql.genreAliasesQueries.insert(raw_string = "space opera", genre_id = "g-s")
                runTest {
                    f.service.mergeGenres(GenreId("g-s"), GenreId("g-t"))

                    val result = f.service.undoGenreMerge(f.onlyReceiptInto("g-t"))

                    result.shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>().data shouldBe
                        MergeUndoResult("g-s", booksRestored = 2, booksSkipped = 0, restoredAtTopLevel = false)
                    f.genres.findById("g-s").shouldNotBeNull().let {
                        it.deletedAt.shouldBeNull()
                        it.path shouldBe "/space-opera"
                    }
                    sql.genreIdsOf("book1") shouldBe listOf("g-s")
                    sql.genreIdsOf("book2") shouldContainExactlyInAnyOrder listOf("g-s", "g-t")
                    sql.genreAliasesQueries.resolve("space opera").executeAsOne() shouldBe "g-s"
                }
            }
        }

        test("undo leaves an alias a curator re-mapped since the merge alone") {
            withSqlDatabase {
                val f = genreFixture(sql, driver)
                sql.seedGenre("g-s", "Space Opera", "space-opera", "/space-opera")
                sql.seedGenre("g-t", "Science Fiction", "science-fiction", "/science-fiction")
                sql.seedGenre("g-o", "Adventure", "adventure", "/adventure")
                sql.genreAliasesQueries.insert(raw_string = "space opera", genre_id = "g-s")
                runTest {
                    f.service.mergeGenres(GenreId("g-s"), GenreId("g-t"))
                    sql.transaction {
                        sql.genreAliasesQueries.deleteByRawString("space opera")
                        sql.genreAliasesQueries.insert(raw_string = "space opera", genre_id = "g-o")
                    }

                    f.service.undoGenreMerge(f.onlyReceiptInto("g-t")).shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>()

                    sql.genreAliasesQueries.resolve("space opera").executeAsOne() shouldBe "g-o"
                }
            }
        }

        test("undo leaves a book removed from the target alone") {
            withSqlDatabase {
                val f = genreFixture(sql, driver)
                sql.seedGenre("g-s", "Space Opera", "space-opera", "/space-opera")
                sql.seedGenre("g-t", "Science Fiction", "science-fiction", "/science-fiction")
                sql.bookGenresQueries.insertIfAbsent("book1", "g-s")
                sql.bookGenresQueries.insertIfAbsent("book2", "g-s")
                runTest {
                    f.service.mergeGenres(GenreId("g-s"), GenreId("g-t"))
                    sql.bookGenresQueries.deleteLink(book_id = "book2", genre_id = "g-t")

                    val result = f.service.undoGenreMerge(f.onlyReceiptInto("g-t"))

                    result.shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>().data.booksSkipped shouldBe 1
                    sql.genreIdsOf("book2").shouldBeEmpty()
                }
            }
        }

        test("undo refuses while a live genre holds the source's name, and the receipt stays open") {
            withSqlDatabase {
                val f = genreFixture(sql, driver)
                sql.seedGenre("g-s", "Space Opera", "space-opera", "/space-opera")
                sql.seedGenre("g-t", "Science Fiction", "science-fiction", "/science-fiction")
                runTest {
                    f.service.mergeGenres(GenreId("g-s"), GenreId("g-t"))
                    sql.seedGenre("g-new", "Space Opera", "space-opera", "/space-opera")
                    val receipt = f.onlyReceiptInto("g-t")

                    f.service
                        .undoGenreMerge(receipt)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<GenreError.MergeSourceNameTaken>()

                    f.onlyReceiptInto("g-t") shouldBe receipt
                }
            }
        }

        test("a genre whose parent is gone comes back at the top level") {
            withSqlDatabase {
                val f = genreFixture(sql, driver)
                sql.seedGenre("g-p", "Fiction", "fiction", "/fiction")
                sql.seedGenre("g-s", "Space Opera", "space-opera", "/fiction/space-opera", parentId = "g-p", depth = 1)
                sql.seedGenre("g-t", "Science Fiction", "science-fiction", "/science-fiction")
                runTest {
                    f.service.mergeGenres(GenreId("g-s"), GenreId("g-t"))
                    f.genres.softDelete(GenreId("g-p"))

                    val result = f.service.undoGenreMerge(f.onlyReceiptInto("g-t"))

                    result.shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>().data.restoredAtTopLevel shouldBe true
                    f.genres.findById("g-s").shouldNotBeNull().let {
                        it.parentId.shouldBeNull()
                        it.path shouldBe "/space-opera"
                        it.depth shouldBe 0
                    }
                }
            }
        }

        test("a genre follows its parent to wherever the parent was moved since") {
            withSqlDatabase {
                val f = genreFixture(sql, driver)
                sql.seedGenre("g-q", "Speculative", "speculative", "/speculative")
                sql.seedGenre("g-p", "Fiction", "fiction", "/fiction")
                sql.seedGenre("g-s", "Space Opera", "space-opera", "/fiction/space-opera", parentId = "g-p", depth = 1)
                sql.seedGenre("g-t", "Science Fiction", "science-fiction", "/science-fiction")
                runTest {
                    f.service.mergeGenres(GenreId("g-s"), GenreId("g-t"))
                    f.service.moveGenre(GenreId("g-p"), GenreId("g-q")).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    f.service
                        .undoGenreMerge(f.onlyReceiptInto("g-t"))
                        .shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>()
                        .data.restoredAtTopLevel shouldBe false

                    f.genres.findById("g-s").shouldNotBeNull().let {
                        it.parentId shouldBe "g-p"
                        it.path shouldBe "/speculative/fiction/space-opera"
                        it.depth shouldBe 2
                    }
                }
            }
        }

        test("undo refusals: already undone, unknown receipt, target merged away") {
            withSqlDatabase {
                val f = genreFixture(sql, driver)
                sql.seedGenre("g-s", "Space Opera", "space-opera", "/space-opera")
                sql.seedGenre("g-t", "Science Fiction", "science-fiction", "/science-fiction")
                sql.seedGenre("g-u", "Fiction", "fiction", "/fiction")
                runTest {
                    f.service.mergeGenres(GenreId("g-s"), GenreId("g-t"))
                    val first = f.onlyReceiptInto("g-t")
                    f.service.mergeGenres(GenreId("g-t"), GenreId("g-u"))

                    f.service
                        .undoGenreMerge(MergeReceiptId("nope"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<GenreError.MergeReceiptNotFound>()
                    f.service
                        .undoGenreMerge(first)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<GenreError.MergeTargetGone>()

                    val second = f.onlyReceiptInto("g-u")
                    f.service.undoGenreMerge(second).shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>()
                    f.service
                        .undoGenreMerge(second)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<GenreError.MergeAlreadyUndone>()
                    f.service.undoGenreMerge(first).shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>()
                }
            }
        }

        test("undo re-publishes every restored book") {
            withSqlDatabase {
                val f = genreFixture(sql, driver)
                sql.seedGenre("g-s", "Space Opera", "space-opera", "/space-opera")
                sql.seedGenre("g-t", "Science Fiction", "science-fiction", "/science-fiction")
                sql.bookGenresQueries.insertIfAbsent("book1", "g-s")
                runTest {
                    f.service.mergeGenres(GenreId("g-s"), GenreId("g-t"))
                    val revisionAfterMerge =
                        f.books
                            .findById(BookId("book1"))
                            .shouldNotBeNull()
                            .revision

                    f.service.undoGenreMerge(f.onlyReceiptInto("g-t")).shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>()

                    val revisionAfterUndo =
                        f.books
                            .findById(BookId("book1"))
                            .shouldNotBeNull()
                            .revision
                    revisionAfterUndo shouldBeGreaterThan revisionAfterMerge
                }
            }
        }

        test("a caller without canEdit can neither list nor undo") {
            withSqlDatabase {
                val f = genreFixture(sql, driver)
                sql.seedTestUser("member", UserRoleColumn.MEMBER, canEdit = false)
                sql.seedGenre("g-s", "Space Opera", "space-opera", "/space-opera")
                sql.seedGenre("g-t", "Science Fiction", "science-fiction", "/science-fiction")
                runTest {
                    f.service.mergeGenres(GenreId("g-s"), GenreId("g-t"))
                    val receipt = f.onlyReceiptInto("g-t")
                    val member = f.service.copyWith(memberPrincipal("member"))

                    member
                        .listMergeReceipts(GenreId("g-t"))
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                    member
                        .undoGenreMerge(receipt)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }
    })

// ── Fixture ────────────────────────────────────────────────────────────────────

private const val ROOT_USER = "test-root"

private class GenreUndoFixture(
    val service: GenreServiceImpl,
    val genres: GenreRepository,
    val books: BookRepository,
    val clock: MutableClock,
) {
    /** The single open receipt naming [target] as the survivor. */
    suspend fun onlyReceiptInto(target: String): MergeReceiptId =
        service
            .listMergeReceipts(GenreId(target))
            .shouldBeInstanceOf<AppResult.Success<List<MergeReceipt>>>()
            .data
            .single()
            .id
}

/** Seeds the library, ROOT user and two books (`book1`, `book2`); returns a ROOT-scoped service. */
private fun genreFixture(
    sql: ListenUpDatabase,
    driver: SqlDriver,
): GenreUndoFixture {
    sql.seedTestLibraryAndFolder()
    sql.seedTestUser(ROOT_USER, UserRoleColumn.ROOT)
    sql.seedTestBook("book1")
    sql.seedTestBook("book2")
    val clock = MutableClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val genreRepo = GenreRepository(sql, bus, registry, clock)
    val bookRepo =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = registry,
            contributorRepository = ContributorRepository(sql, bus, registry),
            seriesRepository = SeriesRepository(sql, bus, registry),
            genreRepository = genreRepo,
            clock = clock,
            bookTagRepository = BookTagRepository(db = sql, bus = bus, registry = registry, driver = driver),
        )
    val service =
        GenreServiceImpl(
            genreRepo,
            bookRepo,
            sql,
            accessPolicy = BookAccessPolicy(sql, driver),
            principal = rootPrincipal(ROOT_USER),
            clock = clock,
        )
    return GenreUndoFixture(service, genreRepo, bookRepo, clock)
}

private fun ListenUpDatabase.seedGenre(
    id: String,
    name: String,
    slug: String,
    path: String,
    parentId: String? = null,
    depth: Int = 0,
) {
    transaction {
        genresQueries.insert(
            id = id,
            name = name,
            slug = slug,
            path = path,
            parent_id = parentId,
            depth = depth.toLong(),
            sort_order = 0L,
            color = null,
            description = null,
            revision = 0L,
            created_at = 0L,
            updated_at = 0L,
            deleted_at = null,
            client_op_id = null,
        )
    }
}

private fun ListenUpDatabase.genreIdsOf(bookId: String): List<String> = bookGenresQueries.genreIdsForBook(bookId).executeAsList()
