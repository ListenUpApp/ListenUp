@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.server.testing.asSqlDatabase

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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
 * Integration tests for [GenreServiceImpl.mergeGenres].
 *
 * Covers the typed-error guards (MergeSelfTarget / NotFound / HasDescendants)
 * plus the cascade behavior (relink junctions with INSERT-OR-IGNORE, repoint
 * aliases, re-upsert affected books, soft-delete source).
 */
class GenreServiceImplMergeTest :
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

        test("mergeGenres returns MergeSelfTarget when source == target") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mergeGenres(GenreId("g-fant"), GenreId("g-fant"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.MergeSelfTarget>()
                }
            }
        }

        test("mergeGenres returns NotFound when source is unknown") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mergeGenres(GenreId("missing"), GenreId("g-target"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("mergeGenres returns NotFound when target is unknown") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-source", name = "Source", slug = "source", path = "/source")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mergeGenres(GenreId("g-source"), GenreId("missing"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("mergeGenres returns NotFound when source is tombstoned") {
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
                    seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mergeGenres(GenreId("g-dead"), GenreId("g-target"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("mergeGenres returns HasDescendants when source has live children") {
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
                    seedGenre("g-target", name = "Other", slug = "other", path = "/other")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mergeGenres(GenreId("g-fic"), GenreId("g-target"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.HasDescendants>()
                }
            }
        }

        test("mergeGenres relinks book_genres from source to target") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                seedTestBook("book2")
                transaction(db) {
                    seedGenre("g-source", name = "Source", slug = "source", path = "/source")
                    seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                    BookGenreTable.insertIfAbsent("book1", "g-source")
                    BookGenreTable.insertIfAbsent("book2", "g-source")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mergeGenres(GenreId("g-source"), GenreId("g-target"))
                    require(result is AppResult.Success)

                    transaction(db) {
                        BookGenreTable.bookIdsForGenre("g-source").shouldBeEmpty()
                        BookGenreTable.bookIdsForGenre("g-target") shouldContainExactlyInAnyOrder
                            listOf("book1", "book2")
                    }
                }
            }
        }

        test("mergeGenres uses INSERT-OR-IGNORE for books linked to both source and target") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book-both")
                transaction(db) {
                    seedGenre("g-source", name = "Source", slug = "source", path = "/source")
                    seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                    BookGenreTable.insertIfAbsent("book-both", "g-source")
                    BookGenreTable.insertIfAbsent("book-both", "g-target")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mergeGenres(GenreId("g-source"), GenreId("g-target"))
                    require(result is AppResult.Success)

                    transaction(db) {
                        val targetBooks = BookGenreTable.bookIdsForGenre("g-target")
                        targetBooks shouldContainExactlyInAnyOrder listOf("book-both")
                        BookGenreTable.bookIdsForGenre("g-source").shouldBeEmpty()
                    }
                }
            }
        }

        test("mergeGenres repoints genre_aliases from source to target") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-source", name = "Source", slug = "source", path = "/source")
                    seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                    GenreAliasTable.addAlias("source-alias-a", "g-source")
                    GenreAliasTable.addAlias("source-alias-b", "g-source")
                    GenreAliasTable.addAlias("target-existing", "g-target")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mergeGenres(GenreId("g-source"), GenreId("g-target"))
                    require(result is AppResult.Success)

                    transaction(db) {
                        GenreAliasTable.aliasesForGenre("g-source").shouldBeEmpty()
                        GenreAliasTable.aliasesForGenre("g-target") shouldContainExactlyInAnyOrder
                            listOf("source-alias-a", "source-alias-b", "target-existing")
                    }
                }
            }
        }

        test("mergeGenres soft-deletes the source genre") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-source", name = "Source", slug = "source", path = "/source")
                    seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.mergeGenres(GenreId("g-source"), GenreId("g-target"))
                    require(result is AppResult.Success)

                    val source = service.getGenre(GenreId("g-source"))
                    (source as AppResult.Success).data shouldBe null

                    transaction(db) {
                        val row =
                            GenreTable.selectAll().where { GenreTable.id eq "g-source" }.firstOrNull()
                        row.shouldNotBeNull()
                        (row[GenreTable.deletedAt] != null) shouldBe true
                    }
                }
            }
        }

        test("mergeGenres re-upserts affected books so they observe the new target genre") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                seedTestBook("book1")
                transaction(db) {
                    seedGenre("g-source", name = "Source", slug = "source", path = "/source")
                    seedGenre("g-target", name = "Target", slug = "target", path = "/target")
                    BookGenreTable.insertIfAbsent("book1", "g-source")
                }
                runTest {
                    val service = makeService(db)
                    service.mergeGenres(GenreId("g-source"), GenreId("g-target"))
                    transaction(db) {
                        BookGenreTable.bookIdsForGenre("g-target") shouldContainExactlyInAnyOrder listOf("book1")
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
