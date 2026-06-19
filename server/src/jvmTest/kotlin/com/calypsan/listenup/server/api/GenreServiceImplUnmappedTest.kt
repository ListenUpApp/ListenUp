@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.server.testing.asSqlDatabase

import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.GenreAliasTable
import com.calypsan.listenup.server.db.GenreTable
import com.calypsan.listenup.server.db.PendingBookGenreTable
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Integration tests for [GenreServiceImpl.listUnmappedStrings] and
 * [GenreServiceImpl.mapUnmappedToGenre]. Covers the spec Path 3 flow end-to-end:
 * curator picks a raw string from the unmapped queue, binds it to a target
 * genre, alias is recorded, every affected book gets a junction row, pending
 * entries are cleared, and affected books are re-upserted so their
 * `BookSyncPayload.genres` reflects the binding.
 */
class GenreServiceImplUnmappedTest :
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

        // ── listUnmappedStrings ───────────────────────────────────────────────

        test("listUnmappedStrings returns empty list when nothing is pending") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.listUnmappedStrings()
                    require(result is AppResult.Success)
                    result.data.shouldBeEmpty()
                }
            }
        }

        test("listUnmappedStrings aggregates by raw_string with bookCount") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                seedTestBook("book3")
                transaction(db) {
                    PendingBookGenreTable.addPending("book1", "Cyberpunk", firstSeenAt = 1_000L)
                    PendingBookGenreTable.addPending("book2", "Cyberpunk", firstSeenAt = 2_000L)
                    PendingBookGenreTable.addPending("book3", "Cyberpunk", firstSeenAt = 3_000L)
                    PendingBookGenreTable.addPending("book1", "Steampunk", firstSeenAt = 4_000L)
                }
                runTest {
                    val service = makeService(db)
                    val result = service.listUnmappedStrings()
                    require(result is AppResult.Success)
                    result.data.map { it.rawString } shouldContainExactly listOf("Cyberpunk", "Steampunk")
                    val byString = result.data.associateBy { it.rawString }
                    byString["Cyberpunk"]?.bookCount shouldBe 3
                    byString["Steampunk"]?.bookCount shouldBe 1
                }
            }
        }

        test("listUnmappedStrings ordered by bookCount desc, then raw_string asc") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                seedTestBook("book3")
                transaction(db) {
                    // "Zeta" has 2 books; "Alpha" has 1; "Beta" has 2.
                    // Order should be: ("Beta", 2), ("Zeta", 2), ("Alpha", 1).
                    PendingBookGenreTable.addPending("book1", "Beta", firstSeenAt = 1L)
                    PendingBookGenreTable.addPending("book2", "Beta", firstSeenAt = 2L)
                    PendingBookGenreTable.addPending("book1", "Zeta", firstSeenAt = 3L)
                    PendingBookGenreTable.addPending("book2", "Zeta", firstSeenAt = 4L)
                    PendingBookGenreTable.addPending("book3", "Alpha", firstSeenAt = 5L)
                }
                runTest {
                    val service = makeService(db)
                    val result = service.listUnmappedStrings()
                    require(result is AppResult.Success)
                    result.data.map { it.rawString } shouldContainExactly listOf("Beta", "Zeta", "Alpha")
                }
            }
        }

        // ── mapUnmappedToGenre ────────────────────────────────────────────────

        test("mapUnmappedToGenre returns NotFound when genreId is unknown") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                transaction(db) {
                    PendingBookGenreTable.addPending("book1", "Cyberpunk", firstSeenAt = 1L)
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mapUnmappedToGenre("Cyberpunk", GenreId("missing"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("mapUnmappedToGenre returns NotFound when genreId is tombstoned") {
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
                    PendingBookGenreTable.addPending("book1", "Cyberpunk", firstSeenAt = 1L)
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mapUnmappedToGenre("Cyberpunk", GenreId("g-dead"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("mapUnmappedToGenre returns UnmappedStringNotFound when no pending row matches") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mapUnmappedToGenre("Cyberpunk", GenreId("g-fant"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.UnmappedStringNotFound>()
                }
            }
        }

        test("mapUnmappedToGenre adds alias + creates book_genres + drops pending rows") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    PendingBookGenreTable.addPending("book1", "Cyberpunk", firstSeenAt = 1L)
                    PendingBookGenreTable.addPending("book2", "Cyberpunk", firstSeenAt = 2L)
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mapUnmappedToGenre("Cyberpunk", GenreId("g-fant"))
                    require(result is AppResult.Success)

                    transaction(db) {
                        // Alias persisted for future scans.
                        GenreAliasTable.aliasesForGenre("g-fant") shouldContainExactlyInAnyOrder
                            listOf("Cyberpunk")
                        // Junction rows created for every affected book.
                        BookGenreTable.bookIdsForGenre("g-fant") shouldContainExactlyInAnyOrder
                            listOf("book1", "book2")
                        // Pending rows for the mapped string are gone.
                        PendingBookGenreTable.bookIdsByRawString("Cyberpunk").shouldBeEmpty()
                    }
                }
            }
        }

        test("mapUnmappedToGenre is safe when book already has the target genre linked") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    BookGenreTable.insertIfAbsent("book1", "g-fant")
                    PendingBookGenreTable.addPending("book1", "Cyberpunk", firstSeenAt = 1L)
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mapUnmappedToGenre("Cyberpunk", GenreId("g-fant"))
                    require(result is AppResult.Success)

                    transaction(db) {
                        // Idempotent: one row, not two.
                        BookGenreTable.genresForBook("book1") shouldContainExactly listOf("g-fant")
                    }
                }
            }
        }

        test("mapUnmappedToGenre leaves other pending strings untouched") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                    PendingBookGenreTable.addPending("book1", "Cyberpunk", firstSeenAt = 1L)
                    PendingBookGenreTable.addPending("book1", "Steampunk", firstSeenAt = 2L)
                }
                runTest {
                    val service = makeService(db)
                    service.mapUnmappedToGenre("Cyberpunk", GenreId("g-fant"))
                    transaction(db) {
                        PendingBookGenreTable.bookIdsByRawString("Cyberpunk").shouldBeEmpty()
                        PendingBookGenreTable.bookIdsByRawString("Steampunk") shouldContainExactly
                            listOf("book1")
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
