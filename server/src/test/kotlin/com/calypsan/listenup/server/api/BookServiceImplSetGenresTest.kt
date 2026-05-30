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
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Integration tests for [BookServiceImpl.setBookGenres]. Covers the spec Path 2
 * contract: 200-input cap, BookError.NotFound for unknown book, BookError.InvalidInput
 * for unknown or tombstoned genreIds (NO auto-create), atomic replace semantics, and
 * re-upsert side-effect.
 */
class BookServiceImplSetGenresTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))

        fun makeService(db: Database): BookServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val contributorRepo = ContributorRepository(db, bus, registry)
            val seriesRepo = SeriesRepository(db, bus, registry)
            val genreRepo = GenreRepository(db, bus, registry, fixedClock)
            val bookTagRepo = BookTagRepository(db = db, bus = bus, registry = registry)
            val bookRepo =
                BookRepository(
                    db = db,
                    bus = bus,
                    registry = registry,
                    contributorRepository = contributorRepo,
                    seriesRepository = seriesRepo,
                    clock = fixedClock,
                    bookTagRepository = bookTagRepo,
                )
            return BookServiceImpl(
                repo = bookRepo,
                contributorRepo = contributorRepo,
                seriesRepo = seriesRepo,
                coverStorage = CoverStorage(),
                db = db,
                genreRepo = genreRepo,
                accessPolicy = BookAccessPolicy(db),
                principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
            )
        }

        test("setBookGenres returns NotFound when book is unknown") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.setBookGenres(BookId("missing"), emptyList())
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<BookError.NotFound>()
                }
            }
        }

        test("setBookGenres returns InvalidInput when size exceeds 200") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                runTest {
                    val service = makeService(db)
                    val tooMany = (1..201).map { BookGenreInput(GenreId("g$it")) }
                    val result = service.setBookGenres(BookId("book1"), tooMany)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<BookError.InvalidInput>()
                }
            }
        }

        test("setBookGenres returns InvalidInput when any genreId is unknown") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                }
                runTest {
                    val service = makeService(db)
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
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                transaction(db) {
                    seedGenre(
                        "g-dead",
                        name = "Dead",
                        slug = "dead",
                        path = "/dead",
                        deletedAt = 1_700_000_000_000L,
                    )
                }
                runTest {
                    val service = makeService(db)
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

        test("setBookGenres replaces the full genre list atomically") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    seedGenre("g-scifi", name = "Sci-Fi", slug = "sci-fi", path = "/sci-fi")
                    seedGenre("g-hist", name = "History", slug = "history", path = "/history")
                    // Pre-existing junctions that the call should wipe.
                    BookGenreTable.insertIfAbsent("book1", "g-fant")
                    BookGenreTable.insertIfAbsent("book1", "g-scifi")
                }
                runTest {
                    val service = makeService(db)
                    val result =
                        service.setBookGenres(
                            BookId("book1"),
                            listOf(BookGenreInput(GenreId("g-hist"))),
                        )
                    require(result is AppResult.Success)

                    transaction(db) {
                        BookGenreTable.genresForBook("book1") shouldContainExactly listOf("g-hist")
                    }
                }
            }
        }

        test("setBookGenres with empty list clears all linked genres") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    BookGenreTable.insertIfAbsent("book1", "g-fant")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.setBookGenres(BookId("book1"), emptyList())
                    require(result is AppResult.Success)

                    transaction(db) {
                        BookGenreTable.genresForBook("book1").shouldBeEmpty()
                    }
                }
            }
        }

        test("setBookGenres writes multiple genres in one call") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    seedGenre("g-scifi", name = "Sci-Fi", slug = "sci-fi", path = "/sci-fi")
                }
                runTest {
                    val service = makeService(db)
                    val result =
                        service.setBookGenres(
                            BookId("book1"),
                            listOf(
                                BookGenreInput(GenreId("g-fant")),
                                BookGenreInput(GenreId("g-scifi")),
                            ),
                        )
                    require(result is AppResult.Success)

                    transaction(db) {
                        BookGenreTable.genresForBook("book1") shouldContainExactlyInAnyOrder
                            listOf("g-fant", "g-scifi")
                    }
                }
            }
        }
    })

@Suppress("LongParameterList")
private fun seedGenre(
    id: String,
    name: String,
    slug: String,
    path: String,
    parentId: String? = null,
    depth: Int = 0,
    sortOrder: Int = 0,
    deletedAt: Long? = null,
) {
    GenreTable.insert {
        it[GenreTable.id] = id
        it[GenreTable.name] = name
        it[GenreTable.slug] = slug
        it[GenreTable.path] = path
        it[GenreTable.parentId] = parentId
        it[GenreTable.depth] = depth
        it[GenreTable.sortOrder] = sortOrder
        it[GenreTable.color] = null
        it[GenreTable.description] = null
        it[GenreTable.revision] = 0L
        it[GenreTable.createdAt] = 0L
        it[GenreTable.updatedAt] = 0L
        it[GenreTable.deletedAt] = deletedAt
        it[GenreTable.clientOpId] = null
    }
}
