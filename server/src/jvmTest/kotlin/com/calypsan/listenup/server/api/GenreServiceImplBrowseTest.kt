@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.asSqlDriver

import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.db.BookGenreTable
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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Integration tests for [GenreServiceImpl.browseBooks].
 *
 * Covers the two branches (direct-only vs descendants), limit clamp `[1, 1000]`,
 * and the genre-existence guard.
 */
class GenreServiceImplBrowseTest :
    FunSpec({

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_730_000_000_000L))

        fun makeService(db: Database): GenreServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val genreRepo = GenreRepository(db.asSqlDatabase(), bus, registry, fixedClock)
            val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, registry)
            val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, registry)
            val bookTagRepo = BookTagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
            val tagRepo = TagRepository(db = db.asSqlDatabase(), bus = bus, registry = registry)
            val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, db.asSqlDatabase(), db)
            val bookRepo =
                BookRepository(
                    db = db.asSqlDatabase(),
                    driver = db.asSqlDriver(),
                    exposedDb = db,
                    bus = bus,
                    registry = registry,
                    contributorRepository = contributorRepo,
                    seriesRepository = seriesRepo,
                    genreRepository = genreRepo,
                    clock = fixedClock,
                    bookTagRepository = bookTagRepo,
                )
            return GenreServiceImpl(genreRepo, bookRepo, reindexer, db.asSqlDatabase(), db, principal = rootPrincipal())
        }

        test("browseBooks returns NotFound when genreId is unknown") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.browseBooks(GenreId("missing"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("browseBooks returns NotFound when genre is tombstoned") {
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
                    val result = service.browseBooks(GenreId("g-dead"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("browseBooks with includeDescendants=false returns directly-linked books only") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-fant")
                seedTestBook("book-epic")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    seedGenre(
                        "g-epic",
                        name = "Epic Fantasy",
                        slug = "epic-fantasy",
                        path = "/fantasy/epic-fantasy",
                        parentId = "g-fant",
                        depth = 1,
                    )
                    BookGenreTable.insertIfAbsent("book-fant", "g-fant")
                    BookGenreTable.insertIfAbsent("book-epic", "g-epic")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.browseBooks(GenreId("g-fant"), includeDescendants = false)
                    require(result is AppResult.Success)
                    result.data.map { it.value } shouldContainExactlyInAnyOrder listOf("book-fant")
                }
            }
        }

        test("browseBooks with includeDescendants=true also returns books linked to descendant genres") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-fant")
                seedTestBook("book-epic")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    seedGenre(
                        "g-epic",
                        name = "Epic Fantasy",
                        slug = "epic-fantasy",
                        path = "/fantasy/epic-fantasy",
                        parentId = "g-fant",
                        depth = 1,
                    )
                    BookGenreTable.insertIfAbsent("book-fant", "g-fant")
                    BookGenreTable.insertIfAbsent("book-epic", "g-epic")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.browseBooks(GenreId("g-fant"), includeDescendants = true)
                    require(result is AppResult.Success)
                    result.data.map { it.value } shouldContainExactlyInAnyOrder listOf("book-fant", "book-epic")
                }
            }
        }

        test("browseBooks includeDescendants safe against /fic vs /fiction path-prefix collision") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-fic")
                seedTestBook("book-fiction")
                transaction(db) {
                    seedGenre("g-fic", name = "Fic", slug = "fic", path = "/fic")
                    seedGenre("g-fiction", name = "Fiction", slug = "fiction", path = "/fiction")
                    BookGenreTable.insertIfAbsent("book-fic", "g-fic")
                    BookGenreTable.insertIfAbsent("book-fiction", "g-fiction")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.browseBooks(GenreId("g-fic"), includeDescendants = true)
                    require(result is AppResult.Success)
                    result.data.map { it.value } shouldContainExactlyInAnyOrder listOf("book-fic")
                }
            }
        }

        test("browseBooks limit is clamped at 1 when caller passes 0 or negative") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                seedTestBook("book3")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    BookGenreTable.insertIfAbsent("book1", "g-fant")
                    BookGenreTable.insertIfAbsent("book2", "g-fant")
                    BookGenreTable.insertIfAbsent("book3", "g-fant")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.browseBooks(GenreId("g-fant"), limit = 0)
                    require(result is AppResult.Success)
                    result.data.size shouldBe 1
                }
            }
        }

        test("browseBooks empty list when genre has no linked books") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-empty", name = "Empty", slug = "empty", path = "/empty")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.browseBooks(GenreId("g-empty"))
                    require(result is AppResult.Success)
                    result.data.shouldBeEmpty()
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
