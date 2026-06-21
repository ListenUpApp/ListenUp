@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.asSqlDriver

import com.calypsan.listenup.api.error.GenreError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
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
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Integration tests for [GenreServiceImpl.moveGenre] — the highest-risk task
 * in the genres slice. Covers the cycle guard, slug-conflict guard at the new
 * parent, the `/fic` vs `/fiction` LIKE collision safety, subtree path rewrite
 * with depth-delta invariants, and reparent-to-root.
 */
class GenreServiceImplMoveTest :
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
            val reindexer = BookSearchReindexer(bookTagRepo, tagRepo, db.asSqlDatabase(), db.asSqlDriver())
            val bookRepo =
                BookRepository(
                    db = db.asSqlDatabase(),
                    driver = db.asSqlDriver(),
                    bus = bus,
                    registry = registry,
                    contributorRepository = contributorRepo,
                    seriesRepository = seriesRepo,
                    genreRepository = genreRepo,
                    clock = fixedClock,
                    bookTagRepository = bookTagRepo,
                )
            return GenreServiceImpl(genreRepo, bookRepo, reindexer, db.asSqlDatabase(), principal = rootPrincipal())
        }

        test("moveGenre returns NotFound when id is unknown") {
            withInMemoryDatabase {
                runTest {
                    val service = makeService(this@withInMemoryDatabase)
                    val result = service.moveGenre(GenreId("missing"), newParentId = null)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("moveGenre returns NotFound when newParentId is unknown") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-fant", name = "Fantasy", slug = "fantasy", path = "/fantasy")
                }
                runTest {
                    val service = makeService(db)
                    val result = service.moveGenre(GenreId("g-fant"), newParentId = GenreId("missing"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.NotFound>()
                }
            }
        }

        test("moveGenre returns MoveSelfDescendant when moving into own subtree") {
            withInMemoryDatabase {
                val db = this
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
                }
                runTest {
                    val service = makeService(db)
                    val result = service.moveGenre(GenreId("g-fant"), newParentId = GenreId("g-epic"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.MoveSelfDescendant>()
                }
            }
        }

        test("moveGenre returns SlugConflict when the target path already exists") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-fic", name = "Fiction", slug = "fiction", path = "/fiction")
                    seedGenre("g-nf", name = "Non-Fiction", slug = "non-fiction", path = "/non-fiction")
                    // A genre already at "/fiction/fantasy"
                    seedGenre(
                        "g-existing",
                        name = "Existing Fantasy",
                        slug = "existing-fantasy",
                        path = "/fiction/fantasy",
                        parentId = "g-fic",
                        depth = 1,
                    )
                    // The genre we're going to move — has slug "fantasy" — under non-fiction
                    seedGenre(
                        "g-mover",
                        name = "Fantasy",
                        slug = "fantasy",
                        path = "/non-fiction/fantasy",
                        parentId = "g-nf",
                        depth = 1,
                    )
                }
                runTest {
                    val service = makeService(db)
                    val result = service.moveGenre(GenreId("g-mover"), newParentId = GenreId("g-fic"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<GenreError.SlugConflict>()
                }
            }
        }

        test("moveGenre rewrites paths + depths for the whole subtree") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-old", name = "Old", slug = "old", path = "/old")
                    seedGenre("g-new", name = "New", slug = "new", path = "/new")
                    seedGenre(
                        "g-fant",
                        name = "Fantasy",
                        slug = "fantasy",
                        path = "/old/fantasy",
                        parentId = "g-old",
                        depth = 1,
                    )
                    seedGenre(
                        "g-epic",
                        name = "Epic Fantasy",
                        slug = "epic-fantasy",
                        path = "/old/fantasy/epic-fantasy",
                        parentId = "g-fant",
                        depth = 2,
                    )
                }
                runTest {
                    val service = makeService(db)
                    val result = service.moveGenre(GenreId("g-fant"), newParentId = GenreId("g-new"))
                    require(result is AppResult.Success)

                    val fant = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data
                    val epic = (service.getGenre(GenreId("g-epic")) as AppResult.Success).data
                    fant?.path shouldBe "/new/fantasy"
                    fant?.depth shouldBe 1
                    fant?.parentId shouldBe "g-new"
                    epic?.path shouldBe "/new/fantasy/epic-fantasy"
                    epic?.depth shouldBe 2
                }
            }
        }

        test("moveGenre to null parent moves the genre to root") {
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
                    val result = service.moveGenre(GenreId("g-fant"), newParentId = null)
                    require(result is AppResult.Success)

                    val fant = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data
                    fant?.path shouldBe "/fantasy"
                    fant?.depth shouldBe 0
                    fant?.parentId shouldBe null
                }
            }
        }

        // Spec acceptance criterion #5: the `/fic` vs `/fiction` LIKE-collision must not
        // sweep up unrelated genres. The descendant-prefix lookup uses `path = ? OR path
        // LIKE ? || '/%'`, which requires a trailing slash and therefore stops at the
        // boundary between `/fic` and `/fiction`.
        test("moveGenre on /fic does not touch /fiction subtree") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-newroot", name = "New Root", slug = "new-root", path = "/new-root")
                    seedGenre("g-fic", name = "Fic", slug = "fic", path = "/fic")
                    seedGenre("g-fiction", name = "Fiction", slug = "fiction", path = "/fiction")
                    seedGenre(
                        "g-fiction-fant",
                        name = "Fantasy",
                        slug = "fantasy",
                        path = "/fiction/fantasy",
                        parentId = "g-fiction",
                        depth = 1,
                    )
                }
                runTest {
                    val service = makeService(db)
                    val result = service.moveGenre(GenreId("g-fic"), newParentId = GenreId("g-newroot"))
                    require(result is AppResult.Success)

                    val fic = (service.getGenre(GenreId("g-fic")) as AppResult.Success).data
                    val fiction = (service.getGenre(GenreId("g-fiction")) as AppResult.Success).data
                    val fictionFant = (service.getGenre(GenreId("g-fiction-fant")) as AppResult.Success).data

                    fic?.path shouldBe "/new-root/fic"
                    fiction?.path shouldBe "/fiction"
                    fictionFant?.path shouldBe "/fiction/fantasy"
                }
            }
        }

        test("moveGenre bumps revision for every node in the subtree") {
            withInMemoryDatabase {
                val db = this
                transaction(db) {
                    seedGenre("g-old", name = "Old", slug = "old", path = "/old")
                    seedGenre("g-new", name = "New", slug = "new", path = "/new")
                    seedGenre(
                        "g-fant",
                        name = "Fantasy",
                        slug = "fantasy",
                        path = "/old/fantasy",
                        parentId = "g-old",
                        depth = 1,
                    )
                    seedGenre(
                        "g-epic",
                        name = "Epic",
                        slug = "epic",
                        path = "/old/fantasy/epic",
                        parentId = "g-fant",
                        depth = 2,
                    )
                }
                runTest {
                    val service = makeService(db)
                    val fantBefore = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data!!.revision
                    val epicBefore = (service.getGenre(GenreId("g-epic")) as AppResult.Success).data!!.revision

                    service.moveGenre(GenreId("g-fant"), newParentId = GenreId("g-new"))

                    val fantAfter = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data!!.revision
                    val epicAfter = (service.getGenre(GenreId("g-epic")) as AppResult.Success).data!!.revision

                    (fantAfter > fantBefore) shouldBe true
                    (epicAfter > epicBefore) shouldBe true
                }
            }
        }

        test("moveGenre with newParent that equals current parent is a no-op") {
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
                    val result = service.moveGenre(GenreId("g-fant"), newParentId = GenreId("g-fic"))
                    // No-op is success; path stays the same.
                    require(result is AppResult.Success)
                    val fant = (service.getGenre(GenreId("g-fant")) as AppResult.Success).data
                    fant?.path shouldBe "/fiction/fantasy"
                    fant?.depth shouldBe 1
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
