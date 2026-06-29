@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Integration tests for [BookServiceImpl.setBookGenres]. Covers the
 * contract: 200-input cap, BookError.NotFound for unknown book, BookError.InvalidInput
 * for unknown or tombstoned genreIds (NO auto-create), atomic replace semantics, and
 * re-upsert side-effect.
 */
class BookServiceImplSetGenresTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))

        fun makeService(db: SqlTestDatabases): BookServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val contributorRepo = ContributorRepository(db.sql, bus, registry)
            val seriesRepo = SeriesRepository(db.sql, bus, registry)
            val genreRepo = GenreRepository(db.sql, bus, registry, fixedClock)
            val bookTagRepo = BookTagRepository(db = db.sql, bus = bus, registry = registry)
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
            return BookServiceImpl(
                repo = bookRepo,
                contributorRepo = contributorRepo,
                seriesRepo = seriesRepo,
                coverStorage = CoverStorage(),
                sql = db.sql,
                genreRepo = genreRepo,
                accessPolicy = BookAccessPolicy(db.sql, db.driver),
                permissionPolicy = UserPermissionPolicy(db.sql),
                principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
            )
        }

        test("setBookGenres returns NotFound when book is unknown") {
            withSqlDatabase {
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result = service.setBookGenres(BookId("missing"), emptyList())
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<BookError.NotFound>()
                }
            }
        }

        test("setBookGenres returns InvalidInput when size exceeds 200") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val tooMany = (1..201).map { BookGenreInput(GenreId("g$it")) }
                    val result = service.setBookGenres(BookId("book1"), tooMany)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<BookError.InvalidInput>()
                }
            }
        }

        test("setBookGenres returns InvalidInput when any genreId is unknown") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result =
                        service.setBookGenres(
                            BookId("book1"),
                            listOf(BookGenreInput(GenreId("g-fant")), BookGenreInput(GenreId("missing"))),
                        )
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<BookError.InvalidInput>()
                }
            }
        }

        test("setBookGenres returns InvalidInput when any genreId is tombstoned") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                seedGenre(
                    "g-dead",
                    name = "Dead",
                    slug = "dead",
                    path = "/dead",
                    deletedAt = 1_700_000_000_000L,
                )
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result =
                        service.setBookGenres(
                            BookId("book1"),
                            listOf(BookGenreInput(GenreId("g-dead"))),
                        )
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<BookError.InvalidInput>()
                }
            }
        }

        test("setBookGenres names the first unknown genreId in input order") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                seedGenre("g-hist", name = "History", slug = "history", path = "/history")
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result =
                        service.setBookGenres(
                            BookId("book1"),
                            listOf(
                                BookGenreInput(GenreId("g-fant")),
                                BookGenreInput(GenreId("missing")),
                                BookGenreInput(GenreId("g-hist")),
                            ),
                        )
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = result.error.shouldBeInstanceOf<BookError.InvalidInput>()
                    (error.debugInfo ?: "") shouldContain "unknownGenre=missing"
                }
            }
        }

        test("setBookGenres replaces the full genre list atomically") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                seedGenre("g-scifi", name = "Sci-Fi", slug = "sci-fi", path = "/sci-fi")
                seedGenre("g-hist", name = "History", slug = "history", path = "/history")
                // Pre-existing junctions that the call should wipe.
                sql.transaction { sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g-fant") }
                sql.transaction { sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g-scifi") }
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result =
                        service.setBookGenres(
                            BookId("book1"),
                            listOf(BookGenreInput(GenreId("g-hist"))),
                        )
                    require(result is AppResult.Success)

                    sql.bookGenresQueries
                        .genresForBook(book_id = "book1")
                        .executeAsList()
                        .map { it.id } shouldContainExactly listOf("g-hist")
                }
            }
        }

        test("setBookGenres with empty list clears all linked genres") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                sql.transaction { sql.bookGenresQueries.insertIfAbsent(book_id = "book1", genre_id = "g-fant") }
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result = service.setBookGenres(BookId("book1"), emptyList())
                    require(result is AppResult.Success)

                    sql.bookGenresQueries
                        .genresForBook(book_id = "book1")
                        .executeAsList()
                        .shouldBeEmpty()
                }
            }
        }

        test("setBookGenres writes multiple genres in one call") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                seedGenre("g-scifi", name = "Sci-Fi", slug = "sci-fi", path = "/sci-fi")
                runTest {
                    val service = makeService(this@withSqlDatabase)
                    val result =
                        service.setBookGenres(
                            BookId("book1"),
                            listOf(
                                BookGenreInput(GenreId("g-fant")),
                                BookGenreInput(GenreId("g-scifi")),
                            ),
                        )
                    require(result is AppResult.Success)

                    sql.bookGenresQueries
                        .genresForBook(book_id = "book1")
                        .executeAsList()
                        .map { it.id } shouldContainExactlyInAnyOrder listOf("g-fant", "g-scifi")
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
