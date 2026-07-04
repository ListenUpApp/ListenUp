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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [GenreServiceImpl.mergeGenres].
 *
 * Covers the typed-error guards (MergeSelfTarget / NotFound / HasDescendants)
 * plus the cascade behavior (relink junctions with INSERT-OR-IGNORE, repoint
 * aliases, re-upsert affected books, soft-delete source).
 */
class GenreServiceImplMergeTest :
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

        test("mergeGenres returns MergeSelfTarget when source == target") {
            withSqlDatabase {
                sql.seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mergeGenres(GenreId("g-fant"), GenreId("g-fant"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.MergeSelfTarget>()
                }
            }
        }

        test("mergeGenres returns NotFound when source is unknown") {
            withSqlDatabase {
                sql.seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mergeGenres(GenreId("missing"), GenreId("g-target"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("mergeGenres returns NotFound when target is unknown") {
            withSqlDatabase {
                sql.seedGenre("g-source", name = "Source", slug = "source", path = "/source")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mergeGenres(GenreId("g-source"), GenreId("missing"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("mergeGenres returns NotFound when source is tombstoned") {
            withSqlDatabase {
                sql.seedGenre(
                    "g-dead",
                    name = "Dead",
                    slug = "dead",
                    path = "/dead",
                    deletedAt = 1_700_000_000_000L,
                )
                sql.seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mergeGenres(GenreId("g-dead"), GenreId("g-target"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("mergeGenres returns HasDescendants when source has live children") {
            withSqlDatabase {
                sql.seedGenre("g-fic", name = "Fiction", slug = "fiction", path = "/fiction")
                sql.seedGenre(
                    "g-fant",
                    name = "Fantasy",
                    slug = "fantasy",
                    path = "/fiction/fantasy",
                    parentId = "g-fic",
                    depth = 1,
                )
                sql.seedGenre("g-target", name = "Other", slug = "other", path = "/other")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mergeGenres(GenreId("g-fic"), GenreId("g-target"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.HasDescendants>()
                }
            }
        }

        test("mergeGenres relinks book_genres from source to target") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedGenre("g-source", name = "Source", slug = "source", path = "/source")
                sql.seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g-source")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book2", genre_id = "g-source")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mergeGenres(GenreId("g-source"), GenreId("g-target"))
                    require(result is AppResult.Success)

                    sql.bookGenresQueries
                        .bookIdsForGenre("g-source")
                        .executeAsList()
                        .shouldBeEmpty()
                    sql.bookGenresQueries.bookIdsForGenre("g-target").executeAsList() shouldContainExactlyInAnyOrder
                        listOf("book1", "book2")
                }
            }
        }

        test("mergeGenres uses INSERT-OR-IGNORE for books linked to both source and target") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-both")
                sql.seedGenre("g-source", name = "Source", slug = "source", path = "/source")
                sql.seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book-both", genre_id = "g-source")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book-both", genre_id = "g-target")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mergeGenres(GenreId("g-source"), GenreId("g-target"))
                    require(result is AppResult.Success)

                    val targetBooks = sql.bookGenresQueries.bookIdsForGenre("g-target").executeAsList()
                    targetBooks shouldContainExactlyInAnyOrder listOf("book-both")
                    sql.bookGenresQueries
                        .bookIdsForGenre("g-source")
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }

        test("mergeGenres repoints genre_aliases from source to target") {
            withSqlDatabase {
                sql.seedGenre("g-source", name = "Source", slug = "source", path = "/source")
                sql.seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                sql.genreAliasesQueries.insert(raw_string = "source-alias-a", genre_id = "g-source")
                sql.genreAliasesQueries.insert(raw_string = "source-alias-b", genre_id = "g-source")
                sql.genreAliasesQueries.insert(raw_string = "target-existing", genre_id = "g-target")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mergeGenres(GenreId("g-source"), GenreId("g-target"))
                    require(result is AppResult.Success)

                    sql.genreAliasesQueries
                        .aliasesForGenre("g-source")
                        .executeAsList()
                        .shouldBeEmpty()
                    sql.genreAliasesQueries.aliasesForGenre("g-target").executeAsList() shouldContainExactlyInAnyOrder
                        listOf("source-alias-a", "source-alias-b", "target-existing")
                }
            }
        }

        test("mergeGenres soft-deletes the source genre") {
            withSqlDatabase {
                sql.seedGenre("g-source", name = "Source", slug = "source", path = "/source")
                sql.seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.mergeGenres(GenreId("g-source"), GenreId("g-target"))
                    require(result is AppResult.Success)

                    val source = service.getGenre(GenreId("g-source"))
                    (source as AppResult.Success).data shouldBe null

                    val row = sql.genresQueries.selectById("g-source").executeAsOneOrNull()
                    row.shouldNotBeNull()
                    (row.deleted_at != null) shouldBe true
                }
            }
        }

        test("mergeGenres re-upserts affected books so they observe the new target genre") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedGenre("g-source", name = "Source", slug = "source", path = "/source")
                sql.seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g-source")
                runTest {
                    val service = makeService(sql, driver)
                    service.mergeGenres(GenreId("g-source"), GenreId("g-target"))
                    sql.bookGenresQueries.bookIdsForGenre("g-target").executeAsList() shouldContainExactlyInAnyOrder listOf("book1")
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
