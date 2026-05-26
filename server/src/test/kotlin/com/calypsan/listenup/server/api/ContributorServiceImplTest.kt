@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class ContributorServiceImplTest :
    FunSpec({

        test("getContributor returns Success with the payload for an existing contributor") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db = db, bus = bus, registry = syncRegistry)
                val service =
                    ContributorServiceImpl(
                        contributorRepo = contributorRepo,
                        bookRepo =
                            BookRepository(
                                db = db,
                                bus = bus,
                                registry = syncRegistry,
                                contributorRepository = contributorRepo,
                                seriesRepository = SeriesRepository(db, bus, syncRegistry),
                            ),
                    )
                runTest {
                    val id = contributorRepo.resolveOrCreate("Brandon Sanderson")

                    val result = service.getContributor(id)

                    val success = result.shouldBeInstanceOf<AppResult.Success<ContributorSyncPayload?>>()
                    success.data shouldNotBe null
                    success.data!!.id shouldBe id.value
                    success.data!!.name shouldBe "Brandon Sanderson"
                }
            }
        }

        test("getContributor returns Success(null) for a non-existent contributor id") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db = db, bus = bus, registry = syncRegistry)
                val service =
                    ContributorServiceImpl(
                        contributorRepo = contributorRepo,
                        bookRepo =
                            BookRepository(
                                db = db,
                                bus = bus,
                                registry = syncRegistry,
                                contributorRepository = contributorRepo,
                                seriesRepository = SeriesRepository(db, bus, syncRegistry),
                            ),
                    )
                runTest {
                    val result = service.getContributor(ContributorId("does-not-exist"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<ContributorSyncPayload?>>()
                    success.data shouldBe null
                }
            }
        }

        test("listBooksByContributor returns all books linked to the contributor") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db = db, bus = bus, registry = syncRegistry)
                val seriesRepo = SeriesRepository(db, bus, syncRegistry)
                val bookRepo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                    )
                val service = ContributorServiceImpl(contributorRepo = contributorRepo, bookRepo = bookRepo)
                runTest {
                    val contributorId = contributorRepo.resolveOrCreate("Brandon Sanderson")
                    bookRepo.upsert(bookFixtureWithContributor("b1", "The Way of Kings", contributorId))
                    bookRepo.upsert(bookFixtureWithContributor("b2", "Words of Radiance", contributorId, rootRelPath = "WoR"))

                    val result = service.listBooksByContributor(contributorId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data shouldHaveSize 2
                }
            }
        }

        test("listBooksByContributor returns empty list when contributor has no books") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db = db, bus = bus, registry = syncRegistry)
                val service =
                    ContributorServiceImpl(
                        contributorRepo = contributorRepo,
                        bookRepo =
                            BookRepository(
                                db = db,
                                bus = bus,
                                registry = syncRegistry,
                                contributorRepository = contributorRepo,
                                seriesRepository = SeriesRepository(db, bus, syncRegistry),
                            ),
                    )
                runTest {
                    val contributorId = contributorRepo.resolveOrCreate("Unknown Author")

                    val result = service.listBooksByContributor(contributorId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data.shouldBeEmpty()
                }
            }
        }
    })

private fun bookFixtureWithContributor(
    id: String,
    title: String,
    contributorId: ContributorId,
    rootRelPath: String = "Sanderson/$title",
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
        hasScanWarning = false,
        totalDuration = 3_600_000L,
        cover = null,
        rootRelPath = rootRelPath,
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors =
            listOf(
                BookContributorPayload(
                    id = contributorId.value,
                    name = "Brandon Sanderson",
                    sortName = null,
                    role = "author",
                    creditedAs = null,
                ),
            ),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af1",
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
                BookChapterPayload(id = "ch1", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
