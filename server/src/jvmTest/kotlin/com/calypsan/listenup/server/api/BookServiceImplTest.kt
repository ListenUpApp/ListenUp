@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.bookPayloadFixture
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.asSqlDriver

class BookServiceImplTest :
    FunSpec({

        test("getBook returns Success with the aggregate for a seeded book") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry)
                val genreRepo = GenreRepository(db.asSqlDatabase(), bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.asSqlDatabase(),
                        driver = db.asSqlDriver(),
                        exposedDb = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                        genreRepository = genreRepo,
                    )
                val service =
                    BookServiceImpl(
                        repo = repo,
                        contributorRepo = contributorRepo,
                        seriesRepo = seriesRepo,
                        coverStorage = CoverStorage(),
                        db = db,
                        genreRepo = genreRepo,
                        accessPolicy = BookAccessPolicy(db.asSqlDatabase(), db.asSqlDriver()),
                        permissionPolicy = UserPermissionPolicy(db.asSqlDatabase()),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    repo.upsert(bookPayloadFixture(id = "b1", title = "The Way of Kings"))

                    val result = service.getBook(BookId("b1"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<BookSyncPayload>>()
                    success.data.id shouldBe "b1"
                    success.data.title shouldBe "The Way of Kings"
                }
            }
        }

        test("getBook returns SyncError.NotFound for an absent book id") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry)
                val genreRepo = GenreRepository(db.asSqlDatabase(), bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.asSqlDatabase(),
                        driver = db.asSqlDriver(),
                        exposedDb = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                        genreRepository = genreRepo,
                    )
                val service =
                    BookServiceImpl(
                        repo = repo,
                        contributorRepo = contributorRepo,
                        seriesRepo = seriesRepo,
                        coverStorage = CoverStorage(),
                        db = db,
                        genreRepo = genreRepo,
                        accessPolicy = BookAccessPolicy(db.asSqlDatabase(), db.asSqlDriver()),
                        permissionPolicy = UserPermissionPolicy(db.asSqlDatabase()),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    val result = service.getBook(BookId("nonexistent"))

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<SyncError.NotFound>()
                    error.domain shouldBe "book"
                    error.entityId shouldBe "nonexistent"
                }
            }
        }

        test("searchBooks returns matching book ids in FTS rank order") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry)
                val genreRepo = GenreRepository(db.asSqlDatabase(), bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.asSqlDatabase(),
                        driver = db.asSqlDriver(),
                        exposedDb = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                        genreRepository = genreRepo,
                    )
                val service =
                    BookServiceImpl(
                        repo = repo,
                        contributorRepo = contributorRepo,
                        seriesRepo = seriesRepo,
                        coverStorage = CoverStorage(),
                        db = db,
                        genreRepo = genreRepo,
                        accessPolicy = BookAccessPolicy(db.asSqlDatabase(), db.asSqlDriver()),
                        permissionPolicy = UserPermissionPolicy(db.asSqlDatabase()),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    repo.upsert(bookPayloadFixture(id = "b1", title = "The Way of Kings"))
                    repo.upsert(bookPayloadFixture(id = "b2", title = "Words of Radiance", rootRelPath = "Sanderson/Words of Radiance"))
                    repo.upsert(bookPayloadFixture(id = "b3", title = "Mistborn", rootRelPath = "Sanderson/Mistborn"))

                    val result = service.searchBooks("Kings", limit = 50)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>()
                    success.data shouldContainExactlyInAnyOrder listOf(BookId("b1"))
                }
            }
        }

        test("searchBooks returns only the id whose title matches the query") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry)
                val genreRepo = GenreRepository(db.asSqlDatabase(), bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.asSqlDatabase(),
                        driver = db.asSqlDriver(),
                        exposedDb = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                        genreRepository = genreRepo,
                    )
                val service =
                    BookServiceImpl(
                        repo = repo,
                        contributorRepo = contributorRepo,
                        seriesRepo = seriesRepo,
                        coverStorage = CoverStorage(),
                        db = db,
                        genreRepo = genreRepo,
                        accessPolicy = BookAccessPolicy(db.asSqlDatabase(), db.asSqlDriver()),
                        permissionPolicy = UserPermissionPolicy(db.asSqlDatabase()),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    repo.upsert(bookPayloadFixture(id = "b1", title = "The Way of Kings"))
                    repo.upsert(bookPayloadFixture(id = "b2", title = "Words of Radiance", rootRelPath = "Sanderson/Words of Radiance"))
                    repo.upsert(bookPayloadFixture(id = "b3", title = "Mistborn", rootRelPath = "Sanderson/Mistborn"))

                    val result = service.searchBooks("Radiance", limit = 50)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>()
                    success.data shouldContainExactlyInAnyOrder listOf(BookId("b2"))
                }
            }
        }

        test("searchBooks with blank query returns empty list without querying all books") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry)
                val genreRepo = GenreRepository(db.asSqlDatabase(), bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.asSqlDatabase(),
                        driver = db.asSqlDriver(),
                        exposedDb = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                        genreRepository = genreRepo,
                    )
                val service =
                    BookServiceImpl(
                        repo = repo,
                        contributorRepo = contributorRepo,
                        seriesRepo = seriesRepo,
                        coverStorage = CoverStorage(),
                        db = db,
                        genreRepo = genreRepo,
                        accessPolicy = BookAccessPolicy(db.asSqlDatabase(), db.asSqlDriver()),
                        permissionPolicy = UserPermissionPolicy(db.asSqlDatabase()),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    repo.upsert(bookPayloadFixture(id = "b1", title = "The Way of Kings"))
                    repo.upsert(bookPayloadFixture(id = "b2", title = "Words of Radiance", rootRelPath = "Sanderson/Words of Radiance"))

                    val result = service.searchBooks("", limit = 50)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookId>>>()
                    success.data.shouldBeEmpty()
                }
            }
        }
    })
