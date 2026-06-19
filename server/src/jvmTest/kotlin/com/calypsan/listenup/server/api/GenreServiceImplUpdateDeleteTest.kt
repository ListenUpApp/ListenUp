@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.server.testing.asSqlDatabase

import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.GenreAliasTable
import com.calypsan.listenup.server.db.GenreTable
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
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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

        fun makeService(db: Database): GenreServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val genreRepo = GenreRepository(db, bus, registry, fixedClock)
            val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, registry)
            val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, registry)
            val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
            val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
            val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, db)
            val bookRepo =
                BookRepository(
                    db = db,
                    bus = bus,
                    registry = registry,
                    contributorRepository = contributorRepo,
                    seriesRepository = seriesRepo,
                    genreRepository = genreRepo,
                    clock = fixedClock,
                    bookTagRepository = bookTagRepo,
                )
            return GenreServiceImpl(genreRepo, bookRepo, reindexer, db, principal = rootPrincipal())
        }

        // ── updateGenre ───────────────────────────────────────────────────────

        test("updateGenre returns NotFound when id is unknown") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
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
            withInMemoryDatabase {
                val db = this
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
                        service.updateGenre(GenreId("g-dead"), GenreUpdate(name = "Reborn"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("updateGenre changes name and preserves slug") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.updateGenre(GenreId("g-fant"), GenreUpdate(name = "High Fantasy"))
                    require(result is AppResult.Success)
                    val payload = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data
                    payload?.name shouldBe "High Fantasy"
                    payload?.slug shouldBe "fantasy"
                }
            }
        }

        test("updateGenre changes description, color, sortOrder independently") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                }
                runTest {
                    val service = makeService(db)
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
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre(
                        "g-fant",
                        name = "Fantasy",
                        slug = "fantasy",
                        path = "/fantasy",
                        sortOrder = 7,
                    )
                }
                runTest {
                    val service = makeService(db)
                    val result = service.updateGenre(GenreId("g-fant"), GenreUpdate())
                    require(result is AppResult.Success)
                    val payload = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data
                    payload?.name shouldBe "Fantasy"
                    payload?.sortOrder shouldBe 7
                }
            }
        }

        test("updateGenre bumps the genre revision (substrate write)") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                }
                runTest {
                    val service = makeService(db)
                    val before = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data!!.revision
                    service.updateGenre(GenreId("g-fant"), GenreUpdate(name = "High Fantasy"))
                    val after = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data!!.revision
                    (after > before) shouldBe true
                }
            }
        }

        // ── deleteGenre ───────────────────────────────────────────────────────

        test("deleteGenre returns NotFound when id is unknown") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.deleteGenre(GenreId("missing"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("deleteGenre returns NotFound when id is already tombstoned") {
            withInMemoryDatabase {
                val db = this
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
                    val result = service.deleteGenre(GenreId("g-dead"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("deleteGenre returns HasDescendants when the genre has live children") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-fic", name = "Fiction", slug = "fiction", path = "/fiction")
                    seedGenre(
                        "g-fant",
                        name = "Fantasy",
                        slug = "fantasy",
                        path = "/fiction/fantasy",
                        parentId = "g-fic",
                        depth = 1,
                    )
                }
                runTest {
                    val service = makeService(db)
                    val result = service.deleteGenre(GenreId("g-fic"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.HasDescendants>()
                }
            }
        }

        test("deleteGenre proceeds when all children are tombstoned") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-fic", name = "Fiction", slug = "fiction", path = "/fiction")
                    seedGenre(
                        "g-dead-child",
                        name = "Dead Child",
                        slug = "dead-child",
                        path = "/fiction/dead-child",
                        parentId = "g-fic",
                        depth = 1,
                        deletedAt = 1_700_000_000_000L,
                    )
                }
                runTest {
                    val service = makeService(db)
                    val result = service.deleteGenre(GenreId("g-fic"))
                    result.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("deleteGenre cascades book_genres + genre_aliases + tombstones the row") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    BookGenreTable.insertIfAbsent("book1", "g-fant")
                    BookGenreTable.insertIfAbsent("book2", "g-fant")
                    GenreAliasTable.addAlias("Fantasy", "g-fant")
                    GenreAliasTable.addAlias("Magic", "g-fant")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.deleteGenre(GenreId("g-fant"))
                    require(result is AppResult.Success)

                    transaction(db) {
                        BookGenreTable.bookIdsForGenre("g-fant").shouldBeEmpty()
                        GenreAliasTable.aliasesForGenre("g-fant").shouldBeEmpty()
                    }
                    val payload = service.getGenre(GenreId("g-fant"))
                    (payload as AppResult.Success).data shouldBe null

                    transaction(db) {
                        val row =
                            GenreTable
                                .selectAll()
                                .where { GenreTable.id eq "g-fant" }
                                .firstOrNull()
                        row.shouldNotBeNull()
                        (row[GenreTable.deletedAt] != null) shouldBe true
                    }
                }
            }
        }

        test("deleteGenre re-upserts affected books so BookSyncPayload.genres reflects the loss") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    seedGenre("g-scifi", name = "Sci-Fi", slug = "sci-fi", path = "/sci-fi")
                    BookGenreTable.insertIfAbsent("book1", "g-fant")
                    BookGenreTable.insertIfAbsent("book1", "g-scifi")
                }
                runTest {
                    val service = makeService(db)
                    service.deleteGenre(GenreId("g-fant"))

                    transaction(db) {
                        val remaining = BookGenreTable.genresForBook("book1")
                        remaining shouldContainExactly listOf("g-scifi")
                        remaining shouldNotContain "g-fant"
                    }
                }
            }
        }

        test("deleteGenre on genre with no books, no aliases still tombstones the row") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-orphan", name = "Orphan", slug = "orphan", path = "/orphan")
                }
                runTest {
                    val service = makeService(db)
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
