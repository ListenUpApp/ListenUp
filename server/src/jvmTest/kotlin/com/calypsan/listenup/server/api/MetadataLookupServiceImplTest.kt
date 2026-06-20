@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.db.LibraryFolderTable
import com.calypsan.listenup.server.db.LibraryTable
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributorProfile
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.metadata.provider.AudibleMetadataProvider
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.CoverSearchService
import com.calypsan.listenup.server.services.GenreAutoCreator
import com.calypsan.listenup.server.services.GenreHierarchyFromLadder
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import org.jetbrains.exposed.v1.jdbc.Database
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.testEnrichmentDeps
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.asSqlDriver

private val NOW = Instant.parse("2026-05-24T12:00:00Z")

class MetadataLookupServiceImplTest :
    FunSpec({

        // ── searchContributorMetadata ─────────────────────────────────────────

        test("searchContributorMetadata wires through to AudibleApi.searchContributors") {
            withInMemoryDatabase {
                val canned =
                    listOf(
                        AudibleContributorProfile(asin = "B001H6L8VC", name = "Stephen King", biography = "", imageUrl = ""),
                        AudibleContributorProfile(asin = "B002ABCDEF", name = "Stephen King Jr.", biography = "", imageUrl = ""),
                    )
                val audible = StubAudibleApi(contributorSearchResult = AppResult.Success(canned))
                val service = makeService(audible = audible, db = this)

                runTest {
                    val result = service.searchContributorMetadata("stephen king")

                    val success = result.shouldBeInstanceOf<AppResult.Success<List<MetadataContributorHit>>>()
                    success.data shouldHaveSize 2
                    success.data[0] shouldBe MetadataContributorHit(asin = "B001H6L8VC", name = "Stephen King")
                    success.data[1] shouldBe MetadataContributorHit(asin = "B002ABCDEF", name = "Stephen King Jr.")
                }
            }
        }

        test("searchContributorMetadata propagates Failure from AudibleApi") {
            withInMemoryDatabase {
                val audible =
                    StubAudibleApi(
                        contributorSearchResult = AppResult.Failure(MetadataError.ExternalUnavailable()),
                    )
                val service = makeService(audible = audible, db = this)

                runTest {
                    val result = service.searchContributorMetadata("anyone")
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
                }
            }
        }

        test("searchContributorMetadata returns empty list when AudibleApi finds nothing") {
            withInMemoryDatabase {
                val audible = StubAudibleApi(contributorSearchResult = AppResult.Success(emptyList()))
                val service = makeService(audible = audible, db = this)

                runTest {
                    val result = service.searchContributorMetadata("unknown author xyz")
                    result
                        .shouldBeInstanceOf<AppResult.Success<List<MetadataContributorHit>>>()
                        .data shouldHaveSize 0
                }
            }
        }

        // ── getBookMetadata iTunes cover enrichment ───────────────────────────

        test("getBookMetadata enriches coverUrlMaxSize from iTunes findCover") {
            withInMemoryDatabase {
                val audible = BookStubAudibleApi(bookWithCover("https://audible.test/cover.jpg"))
                val itunes =
                    StubITunesApi(
                        AppResult.Success(
                            ITunesCoverHit(
                                coverUrl = "https://itunes.test/100x100bb.jpg",
                                maxSizeUrl = "https://itunes.test/7000x7000bb.jpg",
                                sourceId = "12345",
                            ),
                        ),
                    )
                val service = makeService(audible = audible, db = this, itunes = itunes)

                runTest {
                    val result = service.getBookMetadata("B0TESTASIN", AudibleRegion.US)

                    val book = result.shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.api.dto.MetadataBook?>>().data
                    book.shouldNotBeNull()
                    book.coverUrl shouldBe "https://audible.test/cover.jpg"
                    book.coverUrlMaxSize shouldBe "https://itunes.test/7000x7000bb.jpg"
                }
            }
        }

        test("getBookMetadata leaves coverUrlMaxSize null when iTunes finds nothing") {
            withInMemoryDatabase {
                val audible = BookStubAudibleApi(bookWithCover("https://audible.test/cover.jpg"))
                val service = makeService(audible = audible, db = this, itunes = StubITunesApi(AppResult.Success(null)))

                runTest {
                    val result = service.getBookMetadata("B0TESTASIN", AudibleRegion.US)
                    val book = result.shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.api.dto.MetadataBook?>>().data
                    book.shouldNotBeNull()
                    book.coverUrlMaxSize shouldBe null
                }
            }
        }

        test("getBookMetadata still returns the Audible book when the iTunes lookup fails") {
            withInMemoryDatabase {
                val audible = BookStubAudibleApi(bookWithCover("https://audible.test/cover.jpg"))
                val itunes = StubITunesApi(AppResult.Failure(MetadataError.ExternalUnavailable()))
                val service = makeService(audible = audible, db = this, itunes = itunes)

                runTest {
                    val result = service.getBookMetadata("B0TESTASIN", AudibleRegion.US)
                    val book = result.shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.api.dto.MetadataBook?>>().data
                    book.shouldNotBeNull()
                    book.coverUrl shouldBe "https://audible.test/cover.jpg"
                    book.coverUrlMaxSize shouldBe null
                }
            }
        }

        // ── getBookMetadata mood/tag enrichment ───────────────────────────────

        test("getBookMetadata populates moods + tags from the classified product tags") {
            withInMemoryDatabase {
                val audible = BookStubAudibleApi(bookWithCover("https://audible.test/cover.jpg"))
                val productTags =
                    listOf(
                        ProductTag(type = "mood", name = "Feel-Good"),
                        ProductTag(type = "mood", name = "Tense"),
                        ProductTag(type = "theme", name = "Found Family"),
                        ProductTag(type = "genre", name = "Fantasy"), // dropped by the classifier
                    )
                val service = makeService(audible = audible, db = this, productTagSource = { _, _ -> productTags })

                runTest {
                    val result = service.getBookMetadata("B0TESTASIN", AudibleRegion.US)
                    val book = result.shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.api.dto.MetadataBook?>>().data
                    book.shouldNotBeNull()
                    book.moods shouldContainExactlyInAnyOrder listOf("Feel-Good", "Tense")
                    book.tags shouldContainExactlyInAnyOrder listOf("Found Family")
                }
            }
        }

        test("getBookMetadata excludes a theme whose canonical slug matches one of the book's genres") {
            withInMemoryDatabase {
                // The book carries genre "Science Fiction"; a "Sci-Fi" theme canonicalizes to the same slug.
                val audible = BookStubAudibleApi(bookWithGenres(listOf("Science Fiction")))
                val productTags =
                    listOf(
                        ProductTag(type = "theme", name = "Sci-Fi"), // collides with the book genre → dropped
                        ProductTag(type = "theme", name = "Survival"), // no collision → survives
                        ProductTag(type = "mood", name = "Tense"),
                    )
                val service = makeService(audible = audible, db = this, productTagSource = { _, _ -> productTags })

                runTest {
                    val result = service.getBookMetadata("B0TESTASIN", AudibleRegion.US)
                    val book = result.shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.api.dto.MetadataBook?>>().data
                    book.shouldNotBeNull()
                    book.tags shouldContainExactlyInAnyOrder listOf("Survival")
                    book.moods shouldContainExactlyInAnyOrder listOf("Tense")
                }
            }
        }

        test("getBookMetadata leaves moods + tags empty when the product-tag scrape throws") {
            withInMemoryDatabase {
                val audible = BookStubAudibleApi(bookWithCover("https://audible.test/cover.jpg"))
                val service =
                    makeService(audible = audible, db = this, productTagSource = { _, _ -> error("scrape failed") })

                runTest {
                    val result = service.getBookMetadata("B0TESTASIN", AudibleRegion.US)
                    val book = result.shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.api.dto.MetadataBook?>>().data
                    book.shouldNotBeNull()
                    book.moods shouldHaveSize 0
                    book.tags shouldHaveSize 0
                }
            }
        }

        // ── cover-image write location ────────────────────────────────────────

        test("applied book cover is stored in CoverImageStore and not written to the library path") {
            withInMemoryDatabase {
                val db = this
                val imageHome = Files.createTempDirectory("metadata-imagehome-").toAbsolutePath()
                imageHome.toFile().deleteOnExit()
                val coversDir = imageHome.resolve("covers")
                val libraryRoot = Files.createTempDirectory("metadata-library-").toString()
                seedLibraryAndFolder(db, rootPath = libraryRoot)

                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry)
                val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry)
                val genreRepo = GenreRepository(db.asSqlDatabase(), bus, syncRegistry)
                val bookRepo =
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
                val metadataService =
                    MetadataService(
                        audible = BookStubAudibleApi(bookWithCover("https://example.test/cover.jpg")),
                        itunes = NoOpITunesApi(),
                        cache = MetadataCacheRepository(db.asSqlDatabase(), clock = FixedClock(NOW)),
                    )
                // MockEngine returns a minimal valid JPEG so CoverImageStore validation passes.
                val jpegBytes =
                    byteArrayOf(
                        0xFF.toByte(),
                        0xD8.toByte(),
                        0xFF.toByte(),
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                    )
                val imageStorage =
                    ImageStorage(HttpClient(MockEngine { _ -> respond(jpegBytes, HttpStatusCode.OK) }))
                val coverImageStore =
                    CoverImageStore(ImageStore(coversDir, maxBytes = 10L * 1024 * 1024))

                runTest {
                    bookRepo.upsert(bookFixture(bookId = "book-1"))

                    val applier =
                        BookMetadataApplier(
                            bookRepository = bookRepo,
                            contributorRepository = contributorRepo,
                            seriesRepository = seriesRepo,
                            imageStorage = imageStorage,
                            coverImageStore = coverImageStore,
                            metadataProvider = AudibleMetadataProvider(metadataService),
                            genreHierarchy = GenreHierarchyFromLadder(db, genreRepo, GenreAutoCreator(genreRepo)),
                            db = db,
                            ladderSource = { _, _ -> emptyList() },
                            enrichmentDeps = testEnrichmentDeps(db, bus, syncRegistry),
                        )
                    val coverSelection =
                        MetadataApplySelection(
                            title = true,
                            subtitle = true,
                            description = true,
                            publisher = true,
                            releaseDate = true,
                            language = true,
                            cover = true,
                            authorAsins = emptySet(),
                            narratorAsins = emptySet(),
                            seriesAsins = emptySet(),
                        )

                    val result = applier.apply(BookId("book-1"), asin = "B0TESTASIN", region = AudibleRegion.US, selection = coverSelection)
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Cover lands under the managed covers dir …
                    SystemFileSystem.exists(Path(coversDir.toString(), "book-1.jpg")) shouldBe true
                    // … and NOT under the library path.
                    SystemFileSystem.exists(Path(libraryRoot, "covers", "book-1.jpg")) shouldBe false
                }
            }
        }
    })

private fun seedLibraryAndFolder(
    db: Database,
    rootPath: String,
) {
    val now = System.currentTimeMillis()
    transaction(db) {
        LibraryTable.insert {
            it[LibraryTable.id] = "test-library"
            it[LibraryTable.name] = "Test Library"
            it[LibraryTable.createdAt] = now
            it[LibraryTable.updatedAt] = now
            it[LibraryTable.revision] = 0L
            it[LibraryTable.deletedAt] = null
        }
        LibraryFolderTable.insert {
            it[LibraryFolderTable.id] = "test-folder"
            it[LibraryFolderTable.libraryId] = "test-library"
            it[LibraryFolderTable.rootPath] = rootPath
            it[LibraryFolderTable.createdAt] = now
            it[LibraryFolderTable.updatedAt] = now
            it[LibraryFolderTable.revision] = 0L
            it[LibraryFolderTable.deletedAt] = null
        }
    }
}

private fun bookWithCover(coverUrl: String): AudibleBook =
    AudibleBook(
        asin = "B0TESTASIN",
        title = "The Way of Kings",
        subtitle = "",
        authors = emptyList(),
        narrators = emptyList(),
        publisher = "",
        releaseDate = "",
        runtimeMinutes = 0,
        description = "",
        coverUrl = coverUrl,
        series = emptyList(),
        genres = emptyList(),
        language = "",
        rating = 0f,
        ratingCount = 0,
    )

private fun bookWithGenres(genres: List<String>): AudibleBook =
    AudibleBook(
        asin = "B0TESTASIN",
        title = "The Way of Kings",
        subtitle = "",
        authors = emptyList(),
        narrators = emptyList(),
        publisher = "",
        releaseDate = "",
        runtimeMinutes = 0,
        description = "",
        coverUrl = "",
        series = emptyList(),
        genres = genres,
        language = "",
        rating = 0f,
        ratingCount = 0,
    )

private fun bookFixture(bookId: String): BookSyncPayload =
    BookSyncPayload(
        id = bookId,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Original Title",
        sortTitle = "Original Title",
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
        rootRelPath = "Sanderson/WayOfKings",
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af-$bookId",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "aac",
                    duration = 3_600_000L,
                    size = 100L,
                ),
            ),
        chapters =
            listOf(
                BookChapterPayload(id = "ch-$bookId", title = "Prologue", duration = 1_000_000L, startTime = 0L),
            ),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun makeService(
    audible: AudibleApi,
    db: Database,
    itunes: ITunesApi = NoOpITunesApi(),
    productTagSource: suspend (AudibleRegion, String) -> List<ProductTag> = { _, _ -> emptyList() },
): MetadataLookupServiceImpl {
    val tempDir = Files.createTempDirectory("metadata-test-").toAbsolutePath()
    val metadataService =
        MetadataService(
            audible = audible,
            itunes = itunes,
            cache = MetadataCacheRepository(db.asSqlDatabase(), clock = FixedClock(NOW)),
        )
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(db.asSqlDatabase(), bus, syncRegistry)
    val seriesRepo = SeriesRepository(db.asSqlDatabase(), bus, syncRegistry)
    val genreRepo = GenreRepository(db.asSqlDatabase(), bus, syncRegistry)
    val bookRepository =
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
    return MetadataLookupServiceImpl(
        metadataService = metadataService,
        metadataProviders = listOf(AudibleMetadataProvider(metadataService)),
        coverSearchService =
            CoverSearchService(
                readBook = { null },
                providers = emptyList(),
                probeDimensions = { null },
            ),
        bookRepository = bookRepository,
        contributorRepository = contributorRepo,
        seriesRepository = seriesRepo,
        imageDeps =
            MetadataImageDeps(
                imageStorage = ImageStorage(HttpClient(MockEngine { _ -> respond("", HttpStatusCode.OK) })),
                coverImageStore = CoverImageStore(ImageStore(tempDir.resolve("covers"), maxBytes = 10L * 1024 * 1024)),
                imageHome = Path(tempDir.toString()),
            ),
        enrichmentDeps = testEnrichmentDeps(db, bus, syncRegistry, productTagSource = productTagSource),
        permissionPolicy = UserPermissionPolicy(db.asSqlDatabase()),
        db = db,
        genreRepository = genreRepo,
    )
}

private class StubAudibleApi(
    val contributorSearchResult: AppResult<List<AudibleContributorProfile>> = AppResult.Success(emptyList()),
) : AudibleApi {
    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> = AppResult.Success(emptyList())

    override suspend fun getBook(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleBook?> = AppResult.Success(null)

    override suspend fun getChapters(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<AudibleChapter>> = AppResult.Success(emptyList())

    override suspend fun getContributor(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleContributorProfile?> = AppResult.Success(null)

    override suspend fun searchContributors(
        region: AudibleRegion,
        name: String,
    ): AppResult<List<AudibleContributorProfile>> = contributorSearchResult

    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>> = AppResult.Success(emptyList())
}

private class BookStubAudibleApi(
    private val book: AudibleBook,
) : AudibleApi {
    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> = AppResult.Success(emptyList())

    override suspend fun getBook(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleBook?> = AppResult.Success(book)

    override suspend fun getChapters(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<AudibleChapter>> = AppResult.Success(emptyList())

    override suspend fun getContributor(
        region: AudibleRegion,
        asin: String,
    ): AppResult<AudibleContributorProfile?> = AppResult.Success(null)

    override suspend fun searchContributors(
        region: AudibleRegion,
        name: String,
    ): AppResult<List<AudibleContributorProfile>> = AppResult.Success(emptyList())

    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>> = AppResult.Success(emptyList())
}

private class NoOpITunesApi : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)

    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = AppResult.Success(emptyList())
}

private class StubITunesApi(
    private val findCoverResult: AppResult<ITunesCoverHit?>,
) : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = findCoverResult

    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = AppResult.Success(emptyList())
}
