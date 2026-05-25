@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.LibraryRegistry
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

class SeriesServiceImplTest :
    FunSpec({

        test("getSeries returns Success with the payload for an existing series") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val seriesRepo = SeriesRepository(db = db, bus = bus, registry = syncRegistry)
                val service =
                    SeriesServiceImpl(
                        seriesRepo = seriesRepo,
                        bookRepo =
                            BookRepository(
                                db = db,
                                bus = bus,
                                registry = syncRegistry,
                                libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib")),
                                contributorRepository = ContributorRepository(db, bus, syncRegistry),
                                seriesRepository = seriesRepo,
                            ),
                    )
                runTest {
                    val id = seriesRepo.resolveOrCreate("The Stormlight Archive")

                    val result = service.getSeries(id)

                    val success = result.shouldBeInstanceOf<AppResult.Success<SeriesSyncPayload?>>()
                    success.data shouldNotBe null
                    success.data!!.id shouldBe id.value
                    success.data!!.name shouldBe "The Stormlight Archive"
                }
            }
        }

        test("getSeries returns Success(null) for a non-existent series id") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val seriesRepo = SeriesRepository(db = db, bus = bus, registry = syncRegistry)
                val service =
                    SeriesServiceImpl(
                        seriesRepo = seriesRepo,
                        bookRepo =
                            BookRepository(
                                db = db,
                                bus = bus,
                                registry = syncRegistry,
                                libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib")),
                                contributorRepository = ContributorRepository(db, bus, syncRegistry),
                                seriesRepository = seriesRepo,
                            ),
                    )
                runTest {
                    val result = service.getSeries(SeriesId("does-not-exist"))

                    val success = result.shouldBeInstanceOf<AppResult.Success<SeriesSyncPayload?>>()
                    success.data shouldBe null
                }
            }
        }

        test("listBooksBySeries returns all books belonging to the series in position order") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db = db, bus = bus, registry = syncRegistry)
                val seriesRepo = SeriesRepository(db = db, bus = bus, registry = syncRegistry)
                val bookRepo =
                    BookRepository(
                        db = db,
                        bus = bus,
                        registry = syncRegistry,
                        libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib")),
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                    )
                val service = SeriesServiceImpl(seriesRepo = seriesRepo, bookRepo = bookRepo)
                runTest {
                    val seriesId = seriesRepo.resolveOrCreate("The Stormlight Archive")
                    bookRepo.upsert(bookFixtureWithSeries("b1", "The Way of Kings", seriesId, "1"))
                    bookRepo.upsert(bookFixtureWithSeries("b2", "Words of Radiance", seriesId, "2", rootRelPath = "WoR"))

                    val result = service.listBooksBySeries(seriesId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data shouldHaveSize 2
                }
            }
        }

        test("listBooksBySeries returns empty list when series has no books") {
            withInMemoryDatabase {
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val seriesRepo = SeriesRepository(db = db, bus = bus, registry = syncRegistry)
                val service =
                    SeriesServiceImpl(
                        seriesRepo = seriesRepo,
                        bookRepo =
                            BookRepository(
                                db = db,
                                bus = bus,
                                registry = syncRegistry,
                                libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to "/lib")),
                                contributorRepository = ContributorRepository(db, bus, syncRegistry),
                                seriesRepository = seriesRepo,
                            ),
                    )
                runTest {
                    val seriesId = seriesRepo.resolveOrCreate("Empty Series")

                    val result = service.listBooksBySeries(seriesId)

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<BookSyncPayload>>>()
                    success.data.shouldBeEmpty()
                }
            }
        }
    })

private fun bookFixtureWithSeries(
    id: String,
    title: String,
    seriesId: SeriesId,
    sequence: String,
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
        contributors = emptyList(),
        series =
            listOf(
                BookSeriesPayload(
                    id = seriesId.value,
                    name = "The Stormlight Archive",
                    sequence = sequence,
                ),
            ),
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
