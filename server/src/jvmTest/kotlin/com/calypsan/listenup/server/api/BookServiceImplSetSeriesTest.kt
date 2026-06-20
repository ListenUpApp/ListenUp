@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
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
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import com.calypsan.listenup.server.testing.asSqlDatabase

class BookServiceImplSetSeriesTest :
    FunSpec({

        test("setBookSeries replaces the series list with all-existing ids") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry)
                val genreRepo = GenreRepository(db, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.asSqlDatabase(),
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
                        accessPolicy = BookAccessPolicy(db),
                        permissionPolicy = UserPermissionPolicy(db),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    val s1 = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    val s2 = seriesRepo.resolveOrCreate("The Cosmere")
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))

                    val result =
                        service.setBookSeries(
                            BookId("b1"),
                            listOf(
                                BookSeriesInput(id = s1, name = "The Stormlight Archive", position = 1.0),
                                BookSeriesInput(id = s2, name = "The Cosmere", position = 2.0),
                            ),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById(BookId("b1"))!!
                    updated.series shouldHaveSize 2
                    updated.series[0].id shouldBe s1.value
                    updated.series[0].name shouldBe "The Stormlight Archive"
                    updated.series[0].sequence shouldBe "1.0"
                    updated.series[1].id shouldBe s2.value
                    updated.series[1].name shouldBe "The Cosmere"
                    updated.series[1].sequence shouldBe "2.0"
                }
            }
        }

        test("setBookSeries auto-creates an unknown series in the same transaction when id is null") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry)
                val genreRepo = GenreRepository(db, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.asSqlDatabase(),
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
                        accessPolicy = BookAccessPolicy(db),
                        permissionPolicy = UserPermissionPolicy(db),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    val s1 = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))
                    val preCount = seriesRepo.listLiveIds().size

                    val result =
                        service.setBookSeries(
                            BookId("b1"),
                            listOf(
                                BookSeriesInput(id = s1, name = "The Stormlight Archive", position = 1.0),
                                BookSeriesInput(id = null, name = "A Brand New Saga", position = 2.0),
                            ),
                        )

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    seriesRepo.listLiveIds().size shouldBe preCount + 1

                    val updated = repo.findById(BookId("b1"))!!
                    updated.series shouldHaveSize 2
                    updated.series[1].name shouldBe "A Brand New Saga"
                    updated.series[1].sequence shouldBe "2.0"
                }
            }
        }

        test("setBookSeries reduces the series to an empty list") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry)
                val genreRepo = GenreRepository(db, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.asSqlDatabase(),
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
                        accessPolicy = BookAccessPolicy(db),
                        permissionPolicy = UserPermissionPolicy(db),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    val s1 = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    val s2 = seriesRepo.resolveOrCreate("The Cosmere")
                    repo.upsert(
                        bookFixture(id = "b1", title = "The Way of Kings").copy(
                            series =
                                listOf(
                                    BookSeriesPayload(id = s1.value, name = "The Stormlight Archive", sequence = "1"),
                                    BookSeriesPayload(id = s2.value, name = "The Cosmere", sequence = null),
                                ),
                        ),
                    )

                    val result = service.setBookSeries(BookId("b1"), emptyList())

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val updated = repo.findById(BookId("b1"))
                    updated?.series?.shouldBeEmpty()
                }
            }
        }

        test("setBookSeries returns BookError.NotFound when the book does not exist") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry)
                val genreRepo = GenreRepository(db, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.asSqlDatabase(),
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
                        accessPolicy = BookAccessPolicy(db),
                        permissionPolicy = UserPermissionPolicy(db),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    val result =
                        service.setBookSeries(
                            BookId("does-not-exist"),
                            listOf(BookSeriesInput(name = "The Stormlight Archive", position = 1.0)),
                        )

                    val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                    val error = failure.error.shouldBeInstanceOf<BookError.NotFound>()
                    (error.debugInfo ?: "") shouldContain "does-not-exist"
                }
            }
        }

        test("setBookSeries returns BookError.InvalidInput when series size exceeds 200") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry)
                val genreRepo = GenreRepository(db, bus, syncRegistry)
                val repo =
                    BookRepository(
                        db = db.asSqlDatabase(),
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
                        accessPolicy = BookAccessPolicy(db),
                        permissionPolicy = UserPermissionPolicy(db),
                        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
                    )
                runTest {
                    repo.upsert(bookFixture(id = "b1", title = "The Way of Kings"))
                    val tooMany =
                        (0 until 201).map { i ->
                            BookSeriesInput(id = SeriesId("s-$i"), name = "Series $i", position = i.toDouble())
                        }

                    val result = service.setBookSeries(BookId("b1"), tooMany)

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
