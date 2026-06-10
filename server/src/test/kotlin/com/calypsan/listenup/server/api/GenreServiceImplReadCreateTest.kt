@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.BookTable
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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

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

        fun makeService(db: Database): GenreServiceImpl {
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val genreRepo = GenreRepository(db, bus, registry, fixedClock)
            val contributorRepo = ContributorRepository(db, bus, registry)
            val seriesRepo = SeriesRepository(db, bus, registry)
            val bookTagRepo = BookTagRepository(db = db, bus = bus, registry = registry)
            val tagRepo = TagRepository(db = db, bus = bus, registry = registry)
            val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, db)
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
            return GenreServiceImpl(genreRepo, bookRepo, reindexer, db, principal = rootPrincipal())
        }

        // ── listGenres ────────────────────────────────────────────────────────

        test("listGenres returns empty list when no genres exist") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.listGenres()
                    require(result is AppResult.Success)
                    result.data.shouldBeEmpty()
                }
            }
        }

        test("listGenres returns live genres sorted by path") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-fic", name = "Fiction", slug = "fiction", path = "/fiction")
                    seedGenre("g-nf", name = "Non-Fiction", slug = "non-fiction", path = "/non-fiction")
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
                    val result = service.listGenres()
                    require(result is AppResult.Success)
                    result.data.map { it.path } shouldContainExactly
                        listOf("/fiction", "/fiction/fantasy", "/non-fiction")
                }
            }
        }

        test("listGenres excludes tombstoned genres") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-live", name = "Live", slug = "live", path = "/live")
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
                    val result = service.listGenres()
                    require(result is AppResult.Success)
                    result.data.map { it.id.value } shouldContainExactly listOf("g-live")
                }
            }
        }

        test("listGenres computes bookCount via JOIN on book_genres") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                seedTestBook("book3")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    seedGenre("g-scifi", name = "Sci-Fi", slug = "sci-fi", path = "/sci-fi")
                    BookGenreTable.insertIfAbsent("book1", "g-fant")
                    BookGenreTable.insertIfAbsent("book2", "g-fant")
                    BookGenreTable.insertIfAbsent("book3", "g-fant")
                    BookGenreTable.insertIfAbsent("book1", "g-scifi")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.listGenres()
                    require(result is AppResult.Success)
                    val byId = result.data.associateBy { it.id.value }
                    byId["g-fant"]?.bookCount shouldBe 3
                    byId["g-scifi"]?.bookCount shouldBe 1
                }
            }
        }

        test("listGenres counts only live books and includes zero-book genres") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("live1")
                seedTestBook("gone1")
                transaction(db) {
                    seedGenre("g-fic", name = "Fiction", slug = "fiction", path = "/fiction")
                    seedGenre("g-empty", name = "Empty", slug = "empty", path = "/empty")
                    BookGenreTable.insertIfAbsent("live1", "g-fic")
                    BookGenreTable.insertIfAbsent("gone1", "g-fic")
                    BookTable.update({ BookTable.id eq "gone1" }) { it[BookTable.deletedAt] = 123L }
                }
                runTest {
                    val service = makeService(db)
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
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.getGenre(GenreId("missing"))
                    require(result is AppResult.Success)
                    result.data.shouldBeNull()
                }
            }
        }

        test("getGenre returns null when id is tombstoned") {
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
                    val result = service.getGenre(GenreId("g-dead"))
                    require(result is AppResult.Success)
                    result.data.shouldBeNull()
                }
            }
        }

        test("getGenre returns full payload when id is live") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy", depth = 0)
                }
                runTest {
                    val service = makeService(db)
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
                    seedGenre(
                        "g-scifi",
                        name = "Sci-Fi",
                        slug = "sci-fi",
                        path = "/fiction/sci-fi",
                        parentId = "g-fic",
                        depth = 1,
                    )
                    seedGenre(
                        "g-epic",
                        name = "Epic Fantasy",
                        slug = "epic-fantasy",
                        path = "/fiction/fantasy/epic",
                        parentId = "g-fant",
                        depth = 2,
                    )
                }
                runTest {
                    val service = makeService(db)
                    val result = service.getGenreChildren(GenreId("g-fic"))
                    require(result is AppResult.Success)
                    result.data.map { it.id } shouldContainExactlyInAnyOrder listOf("g-fant", "g-scifi")
                }
            }
        }

        test("getGenreChildren excludes tombstoned children") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-fic", name = "Fiction", slug = "fiction", path = "/fiction")
                    seedGenre(
                        "g-live",
                        name = "Live",
                        slug = "live-child",
                        path = "/fiction/live-child",
                        parentId = "g-fic",
                        depth = 1,
                    )
                    seedGenre(
                        "g-dead",
                        name = "Dead",
                        slug = "dead-child",
                        path = "/fiction/dead-child",
                        parentId = "g-fic",
                        depth = 1,
                        deletedAt = 1_700_000_000_000L,
                    )
                }
                runTest {
                    val service = makeService(db)
                    val result = service.getGenreChildren(GenreId("g-fic"))
                    require(result is AppResult.Success)
                    result.data.map { it.id } shouldContainExactly listOf("g-live")
                }
            }
        }

        test("getGenreChildren returns NotFound when parent is missing") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.getGenreChildren(GenreId("missing"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("getGenreChildren returns NotFound when parent is tombstoned") {
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
                    val result = service.getGenreChildren(GenreId("g-dead"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        // ── createGenre ───────────────────────────────────────────────────────

        test("createGenre with null parent creates root genre with path /slug and depth 0") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val service = makeService(db)
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
            withInMemoryDatabase {
                val db = this
                runTest {
                    val service = makeService(db)
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
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.createGenre(parentId = null, name = "", sortOrder = 0)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.InvalidInput>()
                }
            }
        }

        test("createGenre returns InvalidInput when name normalizes to empty") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.createGenre(parentId = null, name = "!!!", sortOrder = 0)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.InvalidInput>()
                }
            }
        }

        test("createGenre returns SlugConflict when slug already in use by a live genre") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val service = makeService(db)
                    val first = service.createGenre(parentId = null, name = "Fantasy", sortOrder = 0)
                    require(first is AppResult.Success)
                    val second = service.createGenre(parentId = null, name = "fantasy", sortOrder = 0)
                    second.shouldBeInstanceOf<AppResult.Failure>()
                    second.error.shouldBeInstanceOf<GenreError.SlugConflict>()
                }
            }
        }

        test("createGenre returns NotFound when parentId is unknown") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result =
                        service.createGenre(parentId = GenreId("missing"), name = "Fantasy", sortOrder = 0)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("createGenre returns NotFound when parentId is tombstoned") {
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
                        service.createGenre(parentId = GenreId("g-dead"), name = "Fantasy", sortOrder = 0)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("createGenre persists sortOrder correctly") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val service = makeService(db)
                    val result = service.createGenre(parentId = null, name = "Fantasy", sortOrder = 42)
                    require(result is AppResult.Success)
                    val payload = (service.getGenre(result.data) as AppResult.Success).data
                    payload?.sortOrder shouldBe 42
                }
            }
        }

        // listGenres includes newly created genres as a smoke check on substrate write paths.
        test("createGenre output is visible via listGenres") {
            withInMemoryDatabase {
                val db = this
                runTest {
                    val service = makeService(db)
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
