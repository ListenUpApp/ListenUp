@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.MergeUndoResult
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
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
    })

// ── Fixture ────────────────────────────────────────────────────────────────────

private const val ROOT_USER = "test-root"

private class GenreUndoFixture(
    val service: GenreServiceImpl,
    val genres: GenreRepository,
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
    return GenreUndoFixture(service, genreRepo, clock)
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
