@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
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
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class BookServiceImplSetContributorsTest :
    FunSpec({

        test("setBookContributors replaces the contributor list with all-existing ids") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.sql, bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.sql, bus, syncRegistry)
                val genreRepo = GenreRepository(db.sql, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.sql,
                        driver = db.driver,
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
                        sql = db.sql,
                        genreRepo = genreRepo,
                        accessPolicy = BookAccessPolicy(db.sql, db.driver),
                        permissionPolicy = UserPermissionPolicy(db.sql),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    val c1 = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    val c2 = contributorRepo.resolveOrCreate("Michael Kramer", sortName = null)
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))

                    val result =
                        service.setBookContributors(
                            BookId("b1"),
                            listOf(
                                BookContributorInput(id = c1, name = "Brandon Sanderson", role = "author", position = 0),
                                BookContributorInput(id = c2, name = "Michael Kramer", role = "narrator", position = 1),
                            ),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById(BookId("b1"))!!
                    updated.contributors shouldHaveSize 2
                    updated.contributors[0].id shouldBe c1.value
                    updated.contributors[0].role shouldBe "author"
                    updated.contributors[1].id shouldBe c2.value
                    updated.contributors[1].role shouldBe "narrator"
                }
            }
        }

        test("setBookContributors auto-creates an unknown contributor in the same transaction when id is null") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.sql, bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.sql, bus, syncRegistry)
                val genreRepo = GenreRepository(db.sql, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.sql,
                        driver = db.driver,
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
                        sql = db.sql,
                        genreRepo = genreRepo,
                        accessPolicy = BookAccessPolicy(db.sql, db.driver),
                        permissionPolicy = UserPermissionPolicy(db.sql),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    val c1 = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))
                    val preCount = contributorRepo.listLiveIds().size

                    val result =
                        service.setBookContributors(
                            BookId("b1"),
                            listOf(
                                BookContributorInput(id = c1, name = "Brandon Sanderson", role = "author", position = 0),
                                BookContributorInput(id = null, name = "Brand New Author", role = "narrator", position = 1),
                            ),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    contributorRepo.listLiveIds().size shouldBe preCount + 1

                    val updated = repo.findById(BookId("b1"))!!
                    updated.contributors shouldHaveSize 2
                    updated.contributors[1].name shouldBe "Brand New Author"
                    updated.contributors[1].role shouldBe "narrator"
                }
            }
        }

        test("setBookContributors reduces the contributors to an empty list") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.sql, bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.sql, bus, syncRegistry)
                val genreRepo = GenreRepository(db.sql, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.sql,
                        driver = db.driver,
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
                        sql = db.sql,
                        genreRepo = genreRepo,
                        accessPolicy = BookAccessPolicy(db.sql, db.driver),
                        permissionPolicy = UserPermissionPolicy(db.sql),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    val c1 = contributorRepo.resolveOrCreate("Brandon Sanderson", sortName = null)
                    val c2 = contributorRepo.resolveOrCreate("Michael Kramer", sortName = null)
                    repo.upsert(
                        bookFixture(id = "b1", title = "The Way of Kings").copy(
                            contributors =
                                listOf(
                                    BookContributorPayload(
                                        id = c1.value,
                                        name = "Brandon Sanderson",
                                        sortName = null,
                                        role = "author",
                                        creditedAs = null,
                                    ),
                                    BookContributorPayload(
                                        id = c2.value,
                                        name = "Michael Kramer",
                                        sortName = null,
                                        role = "narrator",
                                        creditedAs = null,
                                    ),
                                ),
                        ),
                    )

                    val result = service.setBookContributors(BookId("b1"), emptyList())

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById(BookId("b1"))
                    updated?.contributors?.shouldBeEmpty()
                }
            }
        }

        test("setBookContributors returns BookError.NotFound when the book does not exist") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.sql, bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.sql, bus, syncRegistry)
                val genreRepo = GenreRepository(db.sql, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.sql,
                        driver = db.driver,
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
                        sql = db.sql,
                        genreRepo = genreRepo,
                        accessPolicy = BookAccessPolicy(db.sql, db.driver),
                        permissionPolicy = UserPermissionPolicy(db.sql),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    val result =
                        service.setBookContributors(
                            BookId("does-not-exist"),
                            listOf(BookContributorInput(name = "Brandon Sanderson", role = "author", position = 0)),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<BookError.NotFound>()
                    (error.debugInfo ?: "") shouldContain "does-not-exist"
                }
            }
        }

        test("setBookContributors returns BookError.InvalidInput when contributors size exceeds 200") {
            withSqlDatabase {
                val db = this
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.sql, bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.sql, bus, syncRegistry)
                val genreRepo = GenreRepository(db.sql, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.sql,
                        driver = db.driver,
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
                        sql = db.sql,
                        genreRepo = genreRepo,
                        accessPolicy = BookAccessPolicy(db.sql, db.driver),
                        permissionPolicy = UserPermissionPolicy(db.sql),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))
                    val tooMany =
                        (0 until 201).map { i ->
                            BookContributorInput(id = ContributorId("c-$i"), name = "Author $i", role = "author", position = i)
                        }

                    val result = service.setBookContributors(BookId("b1"), tooMany)

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<BookError.InvalidInput>()
                    (error.debugInfo ?: "") shouldContain "201"
                }
            }
        }
    })

private fun bookFixture(
    id: String,
    title: String,
    rootRelPath: String = "Sanderson/$id",
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = title,
        sortTitle = title,
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = rootRelPath,
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af-$id",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "ch-$id", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
