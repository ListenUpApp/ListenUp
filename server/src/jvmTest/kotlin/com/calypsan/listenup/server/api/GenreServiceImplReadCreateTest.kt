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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for the read + create surface of [GenreServiceImpl]:
 * `listGenres`, `getGenre`, `getGenreChildren`, `createGenre`.
 *
 * Uses a real in-memory Flyway-migrated SQLite database + real repositories.
 * Tombstones, hierarchy, and slug conflicts are exercised end-to-end against
 * the live V23 schema.
 */
class GenreServiceImplReadCreateTest :
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

        // ── listGenres ────────────────────────────────────────────────────────

        test("listGenres returns empty list when no genres exist") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.listGenres()
                    require(result is AppResult.Success)
                    result.data.shouldBeEmpty()
                }
            }
        }

        test("listGenres returns live genres sorted by path") {
            withSqlDatabase {
                sql.seedGenre("g-fic", name = "Fiction", slug = "fiction", path = "/fiction")
                sql.seedGenre("g-nf", name = "Non-Fiction", slug = "non-fiction", path = "/non-fiction")
                sql.seedGenre(
                    "g-fant",
                    name = "Fantasy",
                    slug = "fantasy",
                    path = "/fiction/fantasy",
                    parentId = "g-fic",
                    depth = 1,
                )
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.listGenres()
                    require(result is AppResult.Success)
                    result.data.map { it.path } shouldContainExactly
                        listOf("/fiction", "/fiction/fantasy", "/non-fiction")
                }
            }
        }

        test("listGenres excludes tombstoned genres") {
            withSqlDatabase {
                sql.seedGenre("g-live", name = "Live", slug = "live", path = "/live")
                sql.seedGenre(
                    "g-dead",
                    name = "Dead",
                    slug = "dead",
                    path = "/dead",
                    deletedAt = 1_700_000_000_000L,
                )
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.listGenres()
                    require(result is AppResult.Success)
                    result.data.map { it.id.value } shouldContainExactly listOf("g-live")
                }
            }
        }

        test("listGenres computes bookCount via JOIN on book_genres") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestBook("book3")
                sql.seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                sql.seedGenre("g-scifi", name = "Sci-Fi", slug = "sci-fi", path = "/sci-fi")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g-fant")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book2", genre_id = "g-fant")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book3", genre_id = "g-fant")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g-scifi")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.listGenres()
                    require(result is AppResult.Success)
                    val byId = result.data.associateBy { it.id.value }
                    byId["g-fant"]?.bookCount shouldBe 3
                    byId["g-scifi"]?.bookCount shouldBe 1
                }
            }
        }

        test("listGenres counts only live books and includes zero-book genres") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("live1")
                sql.seedTestBook("gone1")
                sql.seedGenre("g-fic", name = "Fiction", slug = "fiction", path = "/fiction")
                sql.seedGenre("g-empty", name = "Empty", slug = "empty", path = "/empty")
                sql.bookGenresQueries.insertIfAbsent(book_id = "live1", genre_id = "g-fic")
                sql.bookGenresQueries.insertIfAbsent(book_id = "gone1", genre_id = "g-fic")
                sql.booksQueries.softDeleteById(
                    id = "gone1",
                    revision = 0L,
                    updated_at = 0L,
                    deleted_at = 123L,
                    client_op_id = null,
                )
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.listGenres()
                    require(result is AppResult.Success)
                    val byId = result.data.associateBy { it.id.value }
                    byId["g-fic"]?.bookCount shouldBe 1
                    byId["g-empty"]?.bookCount shouldBe 0
                }
            }
        }

        // ── getGenre ──────────────────────────────────────────────────────────

        test("getGenre returns null when id is unknown") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.getGenre(GenreId("missing"))
                    require(result is AppResult.Success)
                    result.data.shouldBeNull()
                }
            }
        }

        test("getGenre returns null when id is tombstoned") {
            withSqlDatabase {
                sql.seedGenre(
                    "g-dead",
                    name = "Dead",
                    slug = "dead",
                    path = "/dead",
                    deletedAt = 1_700_000_000_000L,
                )
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.getGenre(GenreId("g-dead"))
                    require(result is AppResult.Success)
                    result.data.shouldBeNull()
                }
            }
        }

        test("getGenre returns full payload when id is live") {
            withSqlDatabase {
                sql.seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy", depth = 0)
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.getGenre(GenreId("g-fant"))
                    require(result is AppResult.Success)
                    val payload = result.data
                    payload?.id shouldBe "g-fant"
                    payload?.name shouldBe "Fantasy"
                    payload?.slug shouldBe "fantasy"
                    payload?.path shouldBe "/fantasy"
                    payload?.depth shouldBe 0
                }
            }
        }

        // ── getGenreChildren ──────────────────────────────────────────────────

        test("getGenreChildren returns direct children only, not descendants") {
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
                sql.seedGenre(
                    "g-scifi",
                    name = "Sci-Fi",
                    slug = "sci-fi",
                    path = "/fiction/sci-fi",
                    parentId = "g-fic",
                    depth = 1,
                )
                sql.seedGenre(
                    "g-epic",
                    name = "Epic Fantasy",
                    slug = "epic-fantasy",
                    path = "/fiction/fantasy/epic",
                    parentId = "g-fant",
                    depth = 2,
                )
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.getGenreChildren(GenreId("g-fic"))
                    require(result is AppResult.Success)
                    result.data.map { it.id } shouldContainExactlyInAnyOrder listOf("g-fant", "g-scifi")
                }
            }
        }

        test("getGenreChildren excludes tombstoned children") {
            withSqlDatabase {
                sql.seedGenre("g-fic", name = "Fiction", slug = "fiction", path = "/fiction")
                sql.seedGenre(
                    "g-live",
                    name = "Live",
                    slug = "live-child",
                    path = "/fiction/live-child",
                    parentId = "g-fic",
                    depth = 1,
                )
                sql.seedGenre(
                    "g-dead",
                    name = "Dead",
                    slug = "dead-child",
                    path = "/fiction/dead-child",
                    parentId = "g-fic",
                    depth = 1,
                    deletedAt = 1_700_000_000_000L,
                )
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.getGenreChildren(GenreId("g-fic"))
                    require(result is AppResult.Success)
                    result.data.map { it.id } shouldContainExactly listOf("g-live")
                }
            }
        }

        test("getGenreChildren returns NotFound when parent is missing") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.getGenreChildren(GenreId("missing"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("getGenreChildren returns NotFound when parent is tombstoned") {
            withSqlDatabase {
                sql.seedGenre(
                    "g-dead",
                    name = "Dead",
                    slug = "dead",
                    path = "/dead",
                    deletedAt = 1_700_000_000_000L,
                )
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.getGenreChildren(GenreId("g-dead"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        // ── createGenre ───────────────────────────────────────────────────────

        test("createGenre with null parent creates root genre with path /slug and depth 0") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.createGenre(parentId = null, name = "Fantasy", sortOrder = 0)
                    require(result is AppResult.Success)
                    val created = service.getGenre(result.data)
                    require(created is AppResult.Success)
                    val payload = created.data
                    payload?.name shouldBe "Fantasy"
                    payload?.slug shouldBe "fantasy"
                    payload?.path shouldBe "/fantasy"
                    payload?.parentId.shouldBeNull()
                    payload?.depth shouldBe 0
                }
            }
        }

        test("createGenre under live parent computes path = parent.path + /slug and depth = parent.depth + 1") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val rootResult = service.createGenre(parentId = null, name = "Fiction", sortOrder = 0)
                    require(rootResult is AppResult.Success)
                    val childResult = service.createGenre(parentId = rootResult.data, name = "Fantasy", sortOrder = 0)
                    require(childResult is AppResult.Success)
                    val grandchildResult =
                        service.createGenre(
                            parentId = childResult.data,
                            name = "Epic Fantasy",
                            sortOrder = 0,
                        )
                    require(grandchildResult is AppResult.Success)

                    val grandchild = (service.getGenre(grandchildResult.data) as AppResult.Success).data
                    grandchild?.path shouldBe "/fiction/fantasy/epic-fantasy"
                    grandchild?.depth shouldBe 2
                    grandchild?.parentId shouldBe childResult.data.value
                }
            }
        }

        test("createGenre returns InvalidInput when name is blank") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.createGenre(parentId = null, name = "", sortOrder = 0)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.InvalidInput>()
                }
            }
        }

        test("createGenre returns InvalidInput when name normalizes to empty") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.createGenre(parentId = null, name = "!!!", sortOrder = 0)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.InvalidInput>()
                }
            }
        }

        test("createGenre returns SlugConflict when slug already in use by a live genre") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val first = service.createGenre(parentId = null, name = "Fantasy", sortOrder = 0)
                    require(first is AppResult.Success)
                    val second = service.createGenre(parentId = null, name = "fantasy", sortOrder = 0)
                    second.shouldBeInstanceOf<AppResult.Failure>()
                    second.error.shouldBeInstanceOf<GenreError.SlugConflict>()
                }
            }
        }

        test("createGenre returns NotFound when parentId is unknown") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result =
                        service.createGenre(parentId = GenreId("missing"), name = "Fantasy", sortOrder = 0)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("createGenre returns NotFound when parentId is tombstoned") {
            withSqlDatabase {
                sql.seedGenre(
                    "g-dead",
                    name = "Dead",
                    slug = "dead",
                    path = "/dead",
                    deletedAt = 1_700_000_000_000L,
                )
                runTest {
                    val service = makeService(sql, driver)
                    val result =
                        service.createGenre(parentId = GenreId("g-dead"), name = "Fantasy", sortOrder = 0)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("createGenre persists sortOrder correctly") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.createGenre(parentId = null, name = "Fantasy", sortOrder = 42)
                    require(result is AppResult.Success)
                    val payload = (service.getGenre(result.data) as AppResult.Success).data
                    payload?.sortOrder shouldBe 42
                }
            }
        }

        // listGenres includes newly created genres as a smoke check on substrate write paths.
        test("createGenre output is visible via listGenres") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    service.createGenre(parentId = null, name = "Fantasy", sortOrder = 0)
                    val all = (service.listGenres() as AppResult.Success).data
                    all shouldHaveSize 1
                    all.first().name shouldBe "Fantasy"
                }
            }
        }
    })

// --- Fixtures ---------------------------------------------------------------

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
