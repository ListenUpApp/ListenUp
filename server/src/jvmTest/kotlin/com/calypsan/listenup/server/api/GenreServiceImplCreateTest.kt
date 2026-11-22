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
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.liveGenre
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [GenreServiceImpl.createGenre].
 *
 * Uses a real in-memory migrated SQLite database + real repositories.
 * Tombstones, hierarchy, and slug conflicts are exercised end-to-end against
 * the live V23 schema.
 */
class GenreServiceImplCreateTest :
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
            val bookTagRepo = BookTagRepository(db = sql, bus = bus, registry = registry, driver = driver)
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
                sql,
                accessPolicy = BookAccessPolicy(sql, driver),
                principal = rootPrincipal(),
            )
        }

        // ── createGenre ───────────────────────────────────────────────────────

        test("createGenre with null parent creates root genre with path /slug and depth 0") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.createGenre(parentId = null, name = "Fantasy", sortOrder = 0)
                    require(result is AppResult.Success)
                    val payload = sql.liveGenre(result.data)
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

                    val grandchild = sql.liveGenre(grandchildResult.data)
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
                    val payload = sql.liveGenre(result.data)
                    payload?.sortOrder shouldBe 42
                }
            }
        }

        // The live genre list includes newly created genres as a smoke check on substrate write paths.
        test("createGenre output is visible among the live genres") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    service.createGenre(parentId = null, name = "Fantasy", sortOrder = 0)
                    val all = sql.genresQueries.listLiveOrderedByPath().executeAsList()
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
