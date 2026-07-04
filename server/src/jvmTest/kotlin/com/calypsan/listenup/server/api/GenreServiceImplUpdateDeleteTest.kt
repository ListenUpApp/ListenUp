@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.GenreUpdate
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
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [GenreServiceImpl.updateGenre] and
 * [GenreServiceImpl.deleteGenre].
 *
 * Uses a real in-memory Flyway-migrated SQLite database + real repositories.
 * The cascade behavior of deleteGenre (book_genres + genre_aliases + book
 * re-upserts) is exercised end-to-end against the live V23 schema.
 */
class GenreServiceImplUpdateDeleteTest :
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

        // ── updateGenre ───────────────────────────────────────────────────────

        test("updateGenre returns NotFound when id is unknown") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result =
                        service.updateGenre(
                            GenreId("missing"),
                            GenreUpdate(name = "Updated"),
                        )
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("updateGenre returns NotFound when id is tombstoned") {
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
                        service.updateGenre(GenreId("g-dead"), GenreUpdate(name = "Reborn"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("updateGenre changes name and preserves slug") {
            withSqlDatabase {
                sql.seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.updateGenre(GenreId("g-fant"), GenreUpdate(name = "High Fantasy"))
                    require(result is AppResult.Success)
                    val payload = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data
                    payload?.name shouldBe "High Fantasy"
                    payload?.slug shouldBe "fantasy"
                }
            }
        }

        test("updateGenre changes description, color, sortOrder independently") {
            withSqlDatabase {
                sql.seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                runTest {
                    val service = makeService(sql, driver)
                    val result =
                        service.updateGenre(
                            GenreId("g-fant"),
                            GenreUpdate(
                                description = "Worlds with magic.",
                                color = "#ff00ff",
                                sortOrder = 42,
                            ),
                        )
                    require(result is AppResult.Success)
                    val payload = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data
                    payload.shouldNotBeNull()
                    payload.description shouldBe "Worlds with magic."
                    payload.color shouldBe "#ff00ff"
                    payload.sortOrder shouldBe 42
                    payload.name shouldBe "Fantasy"
                }
            }
        }

        test("updateGenre with all-null patch leaves payload unchanged") {
            withSqlDatabase {
                sql.seedGenre(
                    "g-fant",
                    name = "Fantasy",
                    slug = "fantasy",
                    path = "/fantasy",
                    sortOrder = 7,
                )
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.updateGenre(GenreId("g-fant"), GenreUpdate())
                    require(result is AppResult.Success)
                    val payload = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data
                    payload?.name shouldBe "Fantasy"
                    payload?.sortOrder shouldBe 7
                }
            }
        }

        test("updateGenre bumps the genre revision (substrate write)") {
            withSqlDatabase {
                sql.seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                runTest {
                    val service = makeService(sql, driver)
                    val before = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data!!.revision
                    service.updateGenre(GenreId("g-fant"), GenreUpdate(name = "High Fantasy"))
                    val after = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data!!.revision
                    (after > before) shouldBe true
                }
            }
        }

        // ── deleteGenre ───────────────────────────────────────────────────────

        test("deleteGenre returns NotFound when id is unknown") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.deleteGenre(GenreId("missing"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("deleteGenre returns NotFound when id is already tombstoned") {
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
                    val result = service.deleteGenre(GenreId("g-dead"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("deleteGenre returns HasDescendants when the genre has live children") {
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
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.deleteGenre(GenreId("g-fic"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.HasDescendants>()
                }
            }
        }

        test("deleteGenre proceeds when all children are tombstoned") {
            withSqlDatabase {
                sql.seedGenre("g-fic", name = "Fiction", slug = "fiction", path = "/fiction")
                sql.seedGenre(
                    "g-dead-child",
                    name = "Dead Child",
                    slug = "dead-child",
                    path = "/fiction/dead-child",
                    parentId = "g-fic",
                    depth = 1,
                    deletedAt = 1_700_000_000_000L,
                )
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.deleteGenre(GenreId("g-fic"))
                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("deleteGenre cascades book_genres + genre_aliases + tombstones the row") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g-fant")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book2", genre_id = "g-fant")
                sql.genreAliasesQueries.insert(raw_string = "Fantasy", genre_id = "g-fant")
                sql.genreAliasesQueries.insert(raw_string = "Magic", genre_id = "g-fant")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.deleteGenre(GenreId("g-fant"))
                    require(result is AppResult.Success)

                    sql.bookGenresQueries
                        .bookIdsForGenre("g-fant")
                        .executeAsList()
                        .shouldBeEmpty()
                    sql.genreAliasesQueries
                        .aliasesForGenre("g-fant")
                        .executeAsList()
                        .shouldBeEmpty()

                    val payload = service.getGenre(GenreId("g-fant"))
                    (payload as AppResult.Success).data shouldBe null

                    val row = sql.genresQueries.selectById("g-fant").executeAsOneOrNull()
                    row.shouldNotBeNull()
                    (row.deleted_at != null) shouldBe true
                }
            }
        }

        test("deleteGenre re-upserts affected books so BookSyncPayload.genres reflects the loss") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                sql.seedGenre("g-scifi", name = "Sci-Fi", slug = "sci-fi", path = "/sci-fi")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g-fant")
                sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g-scifi")
                runTest {
                    val service = makeService(sql, driver)
                    service.deleteGenre(GenreId("g-fant"))

                    val remaining =
                        sql.bookGenresQueries
                            .genresForBook("book1")
                            .executeAsList()
                            .map { it.id }
                    remaining shouldContainExactly listOf("g-scifi")
                    remaining shouldNotContain "g-fant"
                }
            }
        }

        test("deleteGenre on genre with no books, no aliases still tombstones the row") {
            withSqlDatabase {
                sql.seedGenre("g-orphan", name = "Orphan", slug = "orphan", path = "/orphan")
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.deleteGenre(GenreId("g-orphan"))
                    require(result is AppResult.Success)
                    val payload = service.getGenre(GenreId("g-orphan"))
                    (payload as AppResult.Success).data shouldBe null
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
