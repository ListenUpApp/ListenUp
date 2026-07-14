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
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.MetadataLocale
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
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
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.testCoordinator
import com.calypsan.listenup.server.testing.testEnrichmentDeps
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
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

private val NOW = Instant.parse("2026-05-24T12:00:00Z")

class MetadataLookupServiceImplTest :
    FunSpec({

        // ── contributor match: pulled out (Finding #18b) ──────────────────────
        // Contributor auto-match scrapes www.audible.com, which Audible now bot-blocks with a uniform
        // 503. The feature is disabled server-side until a multi-target scraper exists; it returns a
        // clean empty/absent result (never a 503) so manual contributor editing stays the fallback.

        test("searchContributorMetadata is pulled out — returns empty without scraping") {
            withSqlDatabase {
                val canned =
                    listOf(
                        AudibleContributorProfile(asin = "B001H6L8VC", name = "Stephen King", biography = "", imageUrl = ""),
                        AudibleContributorProfile(asin = "B002ABCDEF", name = "Stephen King Jr.", biography = "", imageUrl = ""),
                    )
                // Even though the scrape *would* return two hits, the disabled feature ignores it.
                val audible = StubAudibleApi(contributorSearchResult = AppResult.Success(canned))
                val service = makeService(audible = audible, dbs = this)

                runTest {
                    val result = service.searchContributorMetadata("stephen king")
                    result
                        .shouldBeInstanceOf<AppResult.Success<List<MetadataContributorHit>>>()
                        .data shouldHaveSize 0
                }
            }
        }

        test("searchContributorMetadata returns a clean empty result instead of surfacing the scrape 503") {
            withSqlDatabase {
                val audible =
                    StubAudibleApi(
                        contributorSearchResult = AppResult.Failure(MetadataError.ExternalUnavailable()),
                    )
                val service = makeService(audible = audible, dbs = this)

                runTest {
                    val result = service.searchContributorMetadata("anyone")
                    // Not a Failure — the 503 must never reach the user; auto-match is simply off.
                    result
                        .shouldBeInstanceOf<AppResult.Success<List<MetadataContributorHit>>>()
                        .data shouldHaveSize 0
                }
            }
        }

        test("getContributorMetadata is pulled out — returns null even when the scrape would return a profile") {
            withSqlDatabase {
                val audible =
                    StubAudibleApi(
                        contributorProfileResult =
                            AppResult.Success(
                                AudibleContributorProfile(asin = "B001", name = "Stephen King", biography = "bio", imageUrl = "img"),
                            ),
                    )
                val service = makeService(audible = audible, dbs = this)

                runTest {
                    val result = service.getContributorMetadata("B001", AudibleRegion.US)
                    result
                        .shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.api.dto.MetadataContributorProfile?>>()
                        .data shouldBe null
                }
            }
        }

        // ── getBookMetadata cover composition (Audible search cover + iTunes max-size) ─────────
        // The preview cover composes over the COVER chain [audible, itunes]: the primary URL is
        // Audible's search cover (falling back to iTunes when Audible has none), and the max-size URL
        // is iTunes' high-resolution rendition.

        test("getBookMetadata composes the Audible cover and the iTunes max-size cover") {
            withSqlDatabase {
                val audible = composeAudible(coverSearchUrl = "https://audible.test/cover.jpg")
                val itunes =
                    StubITunesApi(
                        AppResult.Success(
                            listOf(
                                ITunesCoverHit(
                                    coverUrl = "https://itunes.test/100x100bb.jpg",
                                    maxSizeUrl = "https://itunes.test/7000x7000bb.jpg",
                                    sourceId = "12345",
                                ),
                            ),
                        ),
                    )
                val service = makeService(audible = audible, dbs = this, itunes = itunes)

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
            withSqlDatabase {
                val audible = composeAudible(coverSearchUrl = "https://audible.test/cover.jpg")
                val service =
                    makeService(audible = audible, dbs = this, itunes = StubITunesApi(AppResult.Success(emptyList())))

                runTest {
                    val result = service.getBookMetadata("B0TESTASIN", AudibleRegion.US)
                    val book = result.shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.api.dto.MetadataBook?>>().data
                    book.shouldNotBeNull()
                    book.coverUrl shouldBe "https://audible.test/cover.jpg"
                    book.coverUrlMaxSize shouldBe null
                }
            }
        }

        test("getBookMetadata still returns the Audible cover when the iTunes lookup fails") {
            withSqlDatabase {
                val audible = composeAudible(coverSearchUrl = "https://audible.test/cover.jpg")
                val itunes = StubITunesApi(AppResult.Failure(MetadataError.ExternalUnavailable()))
                val service = makeService(audible = audible, dbs = this, itunes = itunes)

                runTest {
                    val result = service.getBookMetadata("B0TESTASIN", AudibleRegion.US)
                    val book = result.shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.api.dto.MetadataBook?>>().data
                    book.shouldNotBeNull()
                    book.coverUrl shouldBe "https://audible.test/cover.jpg"
                    book.coverUrlMaxSize shouldBe null
                }
            }
        }

        // ── getBookMetadata genre composition ─────────────────────────────────

        test("getBookMetadata composes genres from the Audible genre source and leaves moods/tags empty") {
            withSqlDatabase {
                val audible = composeAudible(genres = listOf("Fantasy", "Epic"))
                val service = makeService(audible = audible, dbs = this)

                runTest {
                    val result = service.getBookMetadata("B0TESTASIN", AudibleRegion.US)
                    val book = result.shouldBeInstanceOf<AppResult.Success<com.calypsan.listenup.api.dto.MetadataBook?>>().data
                    book.shouldNotBeNull()
                    book.genres shouldContainExactly listOf("Fantasy", "Epic")
                    book.moods shouldHaveSize 0
                    book.tags shouldHaveSize 0
                }
            }
        }

        // ── cover-image write location ────────────────────────────────────────

        test("applied book cover is stored in CoverImageStore and not written to the library path") {
            withSqlDatabase {
                val imageHome = Files.createTempDirectory("metadata-imagehome-").toAbsolutePath()
                imageHome.toFile().deleteOnExit()
                val coversDir = imageHome.resolve("covers")
                val libraryRoot = Files.createTempDirectory("metadata-library-").toString()
                sql.seedTestLibraryAndFolder(folderPath = libraryRoot)

                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                val contributorRepo = ContributorRepository(sql, bus, syncRegistry)
                val seriesRepo = SeriesRepository(sql, bus, syncRegistry)
                val genreRepo = GenreRepository(sql, bus, syncRegistry)
                val bookRepo =
                    BookRepository(
                        db = sql,
                        driver = driver,
                        bus = bus,
                        registry = syncRegistry,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                        genreRepository = genreRepo,
                    )
                val metadataService =
                    MetadataService(
                        audible = composeAudible(coverSearchUrl = "https://example.test/cover.jpg"),
                        itunes = NoOpITunesApi(),
                        cache = MetadataCacheRepository(sql, clock = FixedClock(NOW)),
                    )
                val coordinator = testCoordinator(metadataService)
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
                    CoverImageStore(ImageStore(Path(coversDir.toString()), maxBytes = 10L * 1024 * 1024))

                runTest {
                    bookRepo.upsert(bookFixture(bookId = "book-1"))

                    val applier =
                        BookMetadataApplier(
                            bookRepository = bookRepo,
                            contributorRepository = contributorRepo,
                            seriesRepository = seriesRepo,
                            imageStorage = imageStorage,
                            coverImageStore = coverImageStore,
                            matchSource = { asin, region ->
                                AppResult.Success(
                                    coordinator
                                        .composeBook(BookIdentity(asin = asin, title = ""), MetadataLocale(region.code))
                                        ?.toMetadataBook(),
                                )
                            },
                            enrichmentProvider = "audible",
                            genreHierarchy =
                                GenreHierarchyFromLadder(sql, genreRepo, GenreAutoCreator(genreRepo)),
                            sqlDb = sql,
                            ladderSource = { _, _ -> emptyList() },
                            enrichmentDeps = testEnrichmentDeps(sql, bus, syncRegistry),
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

private fun audibleBook(
    genres: List<String> = emptyList(),
): AudibleBook =
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

/** A single Audible search hit carrying [coverUrl] for the cover source; other fields are minimal. */
private fun searchHit(coverUrl: String): AudibleSearchResult =
    AudibleSearchResult(
        asin = "B0TESTASIN",
        title = "The Way of Kings",
        subtitle = "",
        authors = emptyList(),
        narrators = emptyList(),
        coverUrl = coverUrl,
        runtimeMinutes = 0,
        releaseDate = "",
    )

/** An Audible API returning one canned book (get) and, when a cover URL is given, one search hit. */
private fun composeAudible(
    coverSearchUrl: String? = null,
    genres: List<String> = emptyList(),
): AudibleApi =
    ComposeStubAudibleApi(
        book = audibleBook(genres = genres),
        searchResults = coverSearchUrl?.let { listOf(searchHit(it)) } ?: emptyList(),
    )

private fun makeService(
    audible: AudibleApi,
    dbs: SqlTestDatabases,
    itunes: ITunesApi = NoOpITunesApi(),
): MetadataLookupServiceImpl {
    val tempDir = Files.createTempDirectory("metadata-test-").toAbsolutePath()
    val metadataService =
        MetadataService(
            audible = audible,
            itunes = itunes,
            cache = MetadataCacheRepository(dbs.sql, clock = FixedClock(NOW)),
        )
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(dbs.sql, bus, syncRegistry)
    val seriesRepo = SeriesRepository(dbs.sql, bus, syncRegistry)
    val genreRepo = GenreRepository(dbs.sql, bus, syncRegistry)
    val bookRepository =
        BookRepository(
            db = dbs.sql,
            driver = dbs.driver,
            bus = bus,
            registry = syncRegistry,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = genreRepo,
        )
    return MetadataLookupServiceImpl(
        metadataService = metadataService,
        coordinator = testCoordinator(metadataService, itunes),
        coverSearchService =
            CoverSearchService(
                readBook = { null },
                registry = MetadataProviderRegistry(emptyList()),
                probeDimensions = { null },
            ),
        bookRepository = bookRepository,
        contributorRepository = contributorRepo,
        seriesRepository = seriesRepo,
        imageDeps =
            MetadataImageDeps(
                imageStorage = ImageStorage(HttpClient(MockEngine { _ -> respond("", HttpStatusCode.OK) })),
                coverImageStore =
                    CoverImageStore(ImageStore(Path(tempDir.resolve("covers").toString()), maxBytes = 10L * 1024 * 1024)),
                imageHome = Path(tempDir.toString()),
            ),
        enrichmentDeps = testEnrichmentDeps(dbs.sql, bus, syncRegistry),
        permissionPolicy = UserPermissionPolicy(dbs.sql),
        sqlDb = dbs.sql,
        genreRepository = genreRepo,
    )
}

private class StubAudibleApi(
    val contributorSearchResult: AppResult<List<AudibleContributorProfile>> = AppResult.Success(emptyList()),
    val contributorProfileResult: AppResult<AudibleContributorProfile?> = AppResult.Success(null),
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
    ): AppResult<AudibleContributorProfile?> = contributorProfileResult

    override suspend fun searchContributors(
        region: AudibleRegion,
        name: String,
    ): AppResult<List<AudibleContributorProfile>> = contributorSearchResult

    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>> = AppResult.Success(emptyList())
}

private class ComposeStubAudibleApi(
    private val book: AudibleBook,
    private val searchResults: List<AudibleSearchResult> = emptyList(),
) : AudibleApi {
    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> = AppResult.Success(searchResults)

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
    private val searchCoversResult: AppResult<List<ITunesCoverHit>>,
) : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)

    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = searchCoversResult
}
