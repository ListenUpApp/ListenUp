@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [GenreServiceImpl.listUnmappedStrings] and
 * [GenreServiceImpl.mapUnmappedToGenre]. Covers the full flow end-to-end:
 * curator picks a raw string from the unmapped queue, binds it to a target
 * genre, alias is recorded, every affected book gets a junction row, pending
 * entries are cleared, and affected books are re-upserted so their
 * `BookSyncPayload.genres` reflects the binding.
 */
class GenreServiceImplUnmappedTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))

        fun makeService(
            sql: ListenUpDatabase,
            driver: SqlDriver,
        ): GenreServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val genreRepo = GenreRepository(sql, bus, registry, fixedClock)
            val contributorRepo = ContributorRepository(sql, bus, registry)
            val seriesRepo = SeriesRepository(sql, bus, registry)
            val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry)
            val tagRepo = TagRepository(db = sql, bus = bus, registry = registry)
            val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, sql, driver)
            val bookRepo =
                BookRepository(
                    db = sql,
                    driver = driver,
                    bus = bus,
                    registry = registry,
                    contributorRepository = contributorRepo,
                    seriesRepository = seriesRepo,
                    genreRepository = genreRepo,
                    clock = fixedClock,
                    bookTagRepository = bookTagRepo,
                )
            return GenreServiceImpl(
                genreRepo,
                bookRepo,
                reindexer,
                sql,
                accessPolicy = BookAccessPolicy(sql, driver),
                principal = rootPrincipal(),
            )
        }

        // ── listUnmappedStrings ───────────────────────────────────────────────

        test("listUnmappedStrings returns empty list when nothing is pending") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.listUnmappedStrings()
                    require(result is AppResult.Success)
                    result.data.shouldBeEmpty()
                }
            }
        }

        test("listUnmappedStrings aggregates by raw_string with bookCount") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestBook("book3")
                sql.pendingBookGenresQueries.addPending(book_id = "book1", raw_string = "Cyberpunk", first_seen_at = 1_000L)
                sql.pendingBookGenresQueries.addPending(book_id = "book2", raw_string = "Cyberpunk", first_seen_at = 2_000L)
                sql.pendingBookGenresQueries.addPending(book_id = "book3", raw_string = "Cyberpunk", first_seen_at = 3_000L)
                sql.pendingBookGenresQueries.addPending(book_id = "book1", raw_string = "Steampunk", first_seen_at = 4_000L)
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.listUnmappedStrings()
                    require(result is AppResult.Success)
                    result.data.map { it.rawString } shouldContainExactly listOf("Cyberpunk", "Steampunk")
                    val byString = result.data.associateBy { it.rawString }
                    byString["Cyberpunk"]?.bookCount shouldBe 3
                    byString["Steampunk"]?.bookCount shouldBe 1
                }
            }
        }

        test("listUnmappedStrings ordered by bookCount desc, then raw_string asc") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestBook("book3")
                // "Zeta" has 2 books; "Alpha" has 1; "Beta" has 2.
                // Order should be: ("Beta", 2), ("Zeta", 2), ("Alpha", 1).
                sql.pendingBookGenresQueries.addPending(book_id = "book1", raw_string = "Beta", first_seen_at = 1L)
                sql.pendingBookGenresQueries.addPending(book_id = "book2", raw_string = "Beta", first_seen_at = 2L)
                sql.pendingBookGenresQueries.addPending(book_id = "book1", raw_string = "Zeta", first_seen_at = 3L)
                sql.pendingBookGenresQueries.addPending(book_id = "book2", raw_string = "Zeta", first_seen_at = 4L)
                sql.pendingBookGenresQueries.addPending(book_id = "book3", raw_string = "Alpha", first_seen_at = 5L)
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.listUnmappedStrings()
                    require(result is AppResult.Success)
                    result.data.map { it.rawString } shouldContainExactly listOf("Beta", "Zeta", "Alpha")
                }
            }
        }

        // ── mapUnmappedToGenre ────────────────────────────────────────────────

        test("mapUnmappedToGenre returns NotFound when genreId is unknown") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.pendingBookGenresQueries.addPending(book_id = "book1", raw_string = "Cyberpunk", first_seen_at = 1L)
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mapUnmappedToGenre("Cyberpunk", GenreId("missing"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("mapUnmappedToGenre returns NotFound when genreId is tombstoned") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedGenre(
                    "g-dead",
                    name = "Dead",
                    slug = "dead",
                    path = "/dead",
                    deletedAt = 1_700_000_000_000L,
                )
                sql.pendingBookGenresQueries.addPending(book_id = "book1", raw_string = "Cyberpunk", first_seen_at = 1L)
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mapUnmappedToGenre("Cyberpunk", GenreId("g-dead"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("mapUnmappedToGenre returns UnmappedStringNotFound when no pending row matches") {
            withSqlDatabase {
                sql.seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mapUnmappedToGenre("Cyberpunk", GenreId("g-fant"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.UnmappedStringNotFound>()
                }
            }
        }

        test("mapUnmappedToGenre adds alias + creates book_genres + drops pending rows") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                sql.pendingBookGenresQueries.addPending(book_id = "book1", raw_string = "Cyberpunk", first_seen_at = 1L)
                sql.pendingBookGenresQueries.addPending(book_id = "book2", raw_string = "Cyberpunk", first_seen_at = 2L)
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mapUnmappedToGenre("Cyberpunk", GenreId("g-fant"))
                    require(result is AppResult.Success)

                    // Alias persisted for future scans.
                    sql.genreAliasesQueries.aliasesForGenre("g-fant").executeAsList() shouldContainExactlyInAnyOrder
                        listOf("Cyberpunk")
                    // Junction rows created for every affected book.
                    sql.bookGenresQueries.bookIdsForGenre("g-fant").executeAsList() shouldContainExactlyInAnyOrder
                        listOf("book1", "book2")
                    // Pending rows for the mapped string are gone.
                    sql.pendingBookGenresQueries
                        .bookIdsByRawString("Cyberpunk")
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }

        test("mapUnmappedToGenre is safe when book already has the target genre linked") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g-fant")
                sql.pendingBookGenresQueries.addPending(book_id = "book1", raw_string = "Cyberpunk", first_seen_at = 1L)
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mapUnmappedToGenre("Cyberpunk", GenreId("g-fant"))
                    require(result is AppResult.Success)

                    // Idempotent: one row, not two.
                    sql.bookGenresQueries
                        .genresForBook("book1")
                        .executeAsList()
                        .map { it.id } shouldContainExactly listOf("g-fant")
                }
            }
        }

        test("mapUnmappedToGenre leaves other pending strings untouched") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                sql.pendingBookGenresQueries.addPending(book_id = "book1", raw_string = "Cyberpunk", first_seen_at = 1L)
                sql.pendingBookGenresQueries.addPending(book_id = "book1", raw_string = "Steampunk", first_seen_at = 2L)
                runTest {
                    val service = makeService(sql, driver)
                    service.mapUnmappedToGenre("Cyberpunk", GenreId("g-fant"))
                    sql.pendingBookGenresQueries
                        .bookIdsByRawString("Cyberpunk")
                        .executeAsList()
                        .shouldBeEmpty()
                    sql.pendingBookGenresQueries.bookIdsByRawString("Steampunk").executeAsList() shouldContainExactly
                        listOf("book1")
                }
            }
        }
    })

@Suppress("LongParameterList")
private fun ListenUpDatabase.seedGenre(
    id: String,
    name: String,
    slug: String,
    path: String,
    parentId: String? = null,
    depth: Int = 0,
    sortOrder: Int = 0,
    deletedAt: Long? = null,
) {
    transaction {
        genresQueries.insert(
            id = id,
            name = name,
            slug = slug,
            path = path,
            parent_id = parentId,
            depth = depth.toLong(),
            sort_order = sortOrder.toLong(),
            color = null,
            description = null,
            revision = 0L,
            created_at = 0L,
            updated_at = 0L,
            deleted_at = deletedAt,
            client_op_id = null,
        )
    }
}
