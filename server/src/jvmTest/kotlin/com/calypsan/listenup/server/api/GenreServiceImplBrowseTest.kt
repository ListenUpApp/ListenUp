@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
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
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [GenreServiceImpl.browseBooks].
 *
 * Covers the two branches (direct-only vs descendants), limit clamp `[1, 1000]`,
 * and the genre-existence guard.
 */
class GenreServiceImplBrowseTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))

        fun makeService(db: SqlTestDatabases): GenreServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val genreRepo = GenreRepository(db.sql, bus, registry, fixedClock)
            val contributorRepo = ContributorRepository(db.sql, bus, registry)
            val seriesRepo = SeriesRepository(db.sql, bus, registry)
            val bookTagRepo = BookTagRepository(db = db.sql, bus = bus, registry = registry)
            val tagRepo = TagRepository(db = db.sql, bus = bus, registry = registry)
            val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, db.sql, db.driver)
            val bookRepo =
                BookRepository(
                    db = db.sql,
                    driver = db.driver,
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
                db.sql,
                accessPolicy = BookAccessPolicy(db.sql, db.driver),
                principal = rootPrincipal(),
            )
        }

        test("browseBooks returns NotFound when genreId is unknown") {
            withSqlDatabase {
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result = service.browseBooks(GenreId("missing"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("browseBooks returns NotFound when genre is tombstoned") {
            withSqlDatabase {
                seedGenre(
                    "g-dead",
                    name = "Dead",
                    slug = "dead",
                    path = "/dead",
                    deletedAt = 1_700_000_000_000L,
                )
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result = service.browseBooks(GenreId("g-dead"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("browseBooks with includeDescendants=false returns directly-linked books only") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-fant")
                sql.seedTestBook("book-epic")
                seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                seedGenre(
                    "g-epic",
                    name = "Epic Fantasy",
                    slug = "epic-fantasy",
                    path = "/fantasy/epic-fantasy",
                    parentId = "g-fant",
                    depth = 1,
                )
                sql.transaction { sql.bookGenresQueries.insertIfAbsent(book_id = "book-fant", genre_id = "g-fant") }
                sql.transaction { sql.bookGenresQueries.insertIfAbsent(book_id = "book-epic", genre_id = "g-epic") }
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result = service.browseBooks(GenreId("g-fant"), includeDescendants = false)
                    require(result is AppResult.Success)
                    result.data.map { it.value } shouldContainExactlyInAnyOrder listOf("book-fant")
                }
            }
        }

        test("browseBooks with includeDescendants=true also returns books linked to descendant genres") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-fant")
                sql.seedTestBook("book-epic")
                seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                seedGenre(
                    "g-epic",
                    name = "Epic Fantasy",
                    slug = "epic-fantasy",
                    path = "/fantasy/epic-fantasy",
                    parentId = "g-fant",
                    depth = 1,
                )
                sql.transaction { sql.bookGenresQueries.insertIfAbsent(book_id = "book-fant", genre_id = "g-fant") }
                sql.transaction { sql.bookGenresQueries.insertIfAbsent(book_id = "book-epic", genre_id = "g-epic") }
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result = service.browseBooks(GenreId("g-fant"), includeDescendants = true)
                    require(result is AppResult.Success)
                    result.data.map { it.value } shouldContainExactlyInAnyOrder listOf("book-fant", "book-epic")
                }
            }
        }

        test("browseBooks includeDescendants safe against /fic vs /fiction path-prefix collision") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book-fic")
                sql.seedTestBook("book-fiction")
                seedGenre("g-fic", name = "Fic", slug = "fic", path = "/fic")
                seedGenre("g-fiction", name = "Fiction", slug = "fiction", path = "/fiction")
                sql.transaction { sql.bookGenresQueries.insertIfAbsent(book_id = "book-fic", genre_id = "g-fic") }
                sql.transaction { sql.bookGenresQueries.insertIfAbsent(book_id = "book-fiction", genre_id = "g-fiction") }
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result = service.browseBooks(GenreId("g-fic"), includeDescendants = true)
                    require(result is AppResult.Success)
                    result.data.map { it.value } shouldContainExactlyInAnyOrder listOf("book-fic")
                }
            }
        }

        test("browseBooks limit is clamped at 1 when caller passes 0 or negative") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                sql.seedTestBook("book3")
                seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                sql.transaction { sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g-fant") }
                sql.transaction { sql.bookGenresQueries.insertIfAbsent(book_id = "book2", genre_id = "g-fant") }
                sql.transaction { sql.bookGenresQueries.insertIfAbsent(book_id = "book3", genre_id = "g-fant") }
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result = service.browseBooks(GenreId("g-fant"), limit = 0)
                    require(result is AppResult.Success)
                    result.data.size shouldBe 1
                }
            }
        }

        test("browseBooks empty list when genre has no linked books") {
            withSqlDatabase {
                seedGenre("g-empty", name = "Empty", slug = "empty", path = "/empty")
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result = service.browseBooks(GenreId("g-empty"))
                    require(result is AppResult.Success)
                    result.data.shouldBeEmpty()
                }
            }
        }
    })

@Suppress("LongParameterList")
private fun SqlTestDatabases.seedGenre(
    id: String,
    name: String,
    slug: String,
    path: String,
    parentId: String? = null,
    depth: Int = 0,
    sortOrder: Int = 0,
    deletedAt: Long? = null,
) {
    sql.transaction {
        sql.genresQueries.insert(
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
