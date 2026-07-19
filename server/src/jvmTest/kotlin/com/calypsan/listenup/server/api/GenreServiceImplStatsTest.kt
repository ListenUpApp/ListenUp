@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.FacetStats
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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [GenreServiceImpl.getGenreStats] and [GenreServiceImpl.getGenreBySlug].
 *
 * Genre tree fixture: Fiction > Fantasy > Epic Fantasy. Uses a real in-memory Flyway-migrated
 * SQLite database + real repositories; no mocks.
 */
class GenreServiceImplStatsTest :
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

        /** Seeds Fiction > Fantasy > Epic Fantasy and returns nothing; tests link books themselves. */
        fun ListenUpDatabase.seedFantasyTree() {
            seedGenre("g-fiction", name = "Fiction", slug = "fiction", path = "/fiction")
            seedGenre(
                "g-fantasy",
                name = "Fantasy",
                slug = "fantasy",
                path = "/fiction/fantasy",
                parentId = "g-fiction",
                depth = 1,
            )
            seedGenre(
                "g-epic",
                name = "Epic Fantasy",
                slug = "epic-fantasy",
                path = "/fiction/fantasy/epic-fantasy",
                parentId = "g-fantasy",
                depth = 2,
            )
        }

        // ── getGenreStats: direct-only ──────────────────────────────────────────

        test("getGenreStats includeDescendants=false counts only directly-linked live books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedFantasyTree()
                sql.seedTestBook("book-fant")
                sql.linkGenre("book-fant", "g-fantasy")
                sql.setDuration("book-fant", 1_000L)

                sql.seedTestBook("book-epic")
                sql.linkGenre("book-epic", "g-epic")
                sql.setDuration("book-epic", 2_000L)

                sql.seedTestBook("book-both")
                sql.linkGenre("book-both", "g-fantasy")
                sql.linkGenre("book-both", "g-epic")
                sql.setDuration("book-both", 3_000L)

                runTest {
                    val service = makeService(sql, driver)
                    val result = service.getGenreStats(GenreId("g-fantasy"), includeDescendants = false)
                    require(result is AppResult.Success)
                    // book-fant + book-both are directly tagged Fantasy; book-epic is not.
                    result.data shouldBe FacetStats(bookCount = 2, totalDurationMs = 4_000L)
                }
            }
        }

        // ── getGenreStats: subtree (includeDescendants) ─────────────────────────

        test("getGenreStats includeDescendants=true rolls up the whole subtree") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedFantasyTree()
                sql.seedTestBook("book-fant")
                sql.linkGenre("book-fant", "g-fantasy")
                sql.setDuration("book-fant", 1_000L)

                sql.seedTestBook("book-epic")
                sql.linkGenre("book-epic", "g-epic")
                sql.setDuration("book-epic", 2_000L)

                runTest {
                    val service = makeService(sql, driver)
                    val result = service.getGenreStats(GenreId("g-fantasy"), includeDescendants = true)
                    require(result is AppResult.Success)
                    result.data shouldBe FacetStats(bookCount = 2, totalDurationMs = 3_000L)
                }
            }
        }

        // ── CRITICAL: dedup — a book tagged with BOTH an ancestor and a descendant genre
        // must count once and contribute its duration once, not twice. A naive SUM over the
        // book_genres⋈genres join would double-count it.

        test("getGenreStats includeDescendants=true dedupes a book tagged with both ancestor and descendant genre") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedFantasyTree()
                sql.seedTestBook("book-both")
                sql.linkGenre("book-both", "g-fantasy")
                sql.linkGenre("book-both", "g-epic")
                sql.setDuration("book-both", 3_000L)

                runTest {
                    val service = makeService(sql, driver)
                    val result = service.getGenreStats(GenreId("g-fantasy"), includeDescendants = true)
                    require(result is AppResult.Success)
                    result.data shouldBe FacetStats(bookCount = 1, totalDurationMs = 3_000L)
                }
            }
        }

        // ── soft-deleted book exclusion ──────────────────────────────────────────

        test("getGenreStats excludes a soft-deleted book from count and length") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedFantasyTree()
                sql.seedTestBook("book-fant")
                sql.linkGenre("book-fant", "g-fantasy")
                sql.setDuration("book-fant", 1_000L)

                sql.seedTestBook("book-dead")
                sql.linkGenre("book-dead", "g-fantasy")
                sql.setDuration("book-dead", 5_000L)
                sql.softDeleteBook("book-dead")

                runTest {
                    val service = makeService(sql, driver)
                    val direct = service.getGenreStats(GenreId("g-fantasy"), includeDescendants = false)
                    require(direct is AppResult.Success)
                    direct.data shouldBe FacetStats(bookCount = 1, totalDurationMs = 1_000L)

                    val subtree = service.getGenreStats(GenreId("g-fantasy"), includeDescendants = true)
                    require(subtree is AppResult.Success)
                    subtree.data shouldBe FacetStats(bookCount = 1, totalDurationMs = 1_000L)
                }
            }
        }

        // ── empty genre ───────────────────────────────────────────────────────────

        test("getGenreStats returns EMPTY for a genre with no linked books") {
            withSqlDatabase {
                sql.seedGenre("g-empty", name = "Empty", slug = "empty", path = "/empty")
                runTest {
                    val service = makeService(sql, driver)
                    val direct = service.getGenreStats(GenreId("g-empty"), includeDescendants = false)
                    require(direct is AppResult.Success)
                    direct.data shouldBe FacetStats.EMPTY

                    val subtree = service.getGenreStats(GenreId("g-empty"), includeDescendants = true)
                    require(subtree is AppResult.Success)
                    subtree.data shouldBe FacetStats.EMPTY
                }
            }
        }

        // ── missing genre ─────────────────────────────────────────────────────────

        test("getGenreStats returns NotFound when genreId is unknown") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.getGenreStats(GenreId("missing"), includeDescendants = false)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        // ── getGenreBySlug ────────────────────────────────────────────────────────

        test("getGenreBySlug returns the matching live genre summary") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedFantasyTree()
                sql.seedTestBook("book-fant")
                sql.linkGenre("book-fant", "g-fantasy")

                runTest {
                    val service = makeService(sql, driver)
                    val result = service.getGenreBySlug("fantasy")
                    require(result is AppResult.Success)
                    result.data.shouldNotBeNull()
                    result.data!!.id shouldBe GenreId("g-fantasy")
                    result.data!!.name shouldBe "Fantasy"
                    result.data!!.slug shouldBe "fantasy"
                    result.data!!.path shouldBe "/fiction/fantasy"
                    result.data!!.bookCount shouldBe 1
                }
            }
        }

        test("getGenreBySlug returns Success(null) for an unknown slug") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, driver)
                    val result = service.getGenreBySlug("no-such-slug")
                    require(result is AppResult.Success)
                    result.data.shouldBeNull()
                }
            }
        }

        test("getGenreBySlug returns Success(null) for a soft-deleted genre's slug") {
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
                    val result = service.getGenreBySlug("dead")
                    require(result is AppResult.Success)
                    result.data.shouldBeNull()
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

private fun ListenUpDatabase.linkGenre(
    bookId: String,
    genreId: String,
) {
    transaction { bookGenresQueries.insertIfAbsent(book_id = bookId, genre_id = genreId) }
}

private fun ListenUpDatabase.setDuration(
    bookId: String,
    durationMs: Long,
) {
    transaction { booksQueries.updateTotalDuration(total_duration = durationMs, id = bookId) }
}

private fun ListenUpDatabase.softDeleteBook(bookId: String) {
    transaction {
        booksQueries.softDeleteById(
            revision = 2L,
            updated_at = 1_700_000_000_000L,
            deleted_at = 1_700_000_000_000L,
            client_op_id = null,
            id = bookId,
        )
    }
}
