@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.dto.MetadataApplySelection
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.MetadataImageDeps
import com.calypsan.listenup.server.api.MetadataLookupServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributor
import com.calypsan.listenup.server.metadata.audible.AudibleContributorProfile
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.services.CoverSearchService
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

private val TEST_NOW = Instant.parse("2026-05-24T12:00:00Z")
private const val TEST_ASIN = "B017V4IM1G"

// Minimal valid JPEG bytes (FF D8 FF E0 … 3-byte magic minimum for sniff).
private val TINY_JPEG =
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

/**
 * Tier 2 E2E test for the Books-B2a metadata-apply path.
 *
 * Exercises the full `MetadataLookupServiceImpl.applyBookMetadata` call
 * chain with:
 *  - A stubbed [AudibleApi] returning a canned [AudibleBook].
 *  - A [MockEngine]-backed [ImageStorage] that records the download URL
 *    and returns a tiny valid JPEG.
 *  - A real [CoverImageStore] backed by a temp directory so the cover lands on disk.
 *  - Real [BookRepository], [ContributorRepository], and [SeriesRepository]
 *    against an in-memory SQLite database.
 *  - Real [MetadataCacheRepository] and [MetadataService] so caching
 *    semantics are exercised (not bypassed).
 *
 * After `applyBookMetadata` returns [AppResult.Success], the test asserts
 * the server-side [BookRepository] row carries the Audible metadata —
 * specifically `description` populated, `asin` stamped, and (new in Task 6)
 * `cover_source = enriched` with `cover_path` and `cover_hash` set.
 *
 * This test lives in `server/src/jvmTest/` (not `sharedLogic/src/jvmTest/`)
 * because [MetadataLookupServiceImpl] is server-only and the full in-process
 * cross-stack test would require extending `withClientSyncEngineAgainstServer`
 * with metadata module wiring — deferred to Books-B2b when the metadata UI
 * surface lands.
 */
class B2aMetadataApplyE2ETest :
    FunSpec({

        test("applyBookMetadata enriches the server book row and returns Success") {
            withSqlDatabase {
                val tempDir = Files.createTempDirectory("b2a-e2e-").also { it.toFile().deleteOnExit() }
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()

                // ── Repositories ────────────────────────────────────────────────
                val contributorRepo = ContributorRepository(sql, bus, syncRegistry)
                val seriesRepo = SeriesRepository(sql, bus, syncRegistry)
                val genreRepo = GenreRepository(sql, bus, syncRegistry)
                val bookRepo =
                    BookRepository(sql, bus, syncRegistry, driver, contributorRepo, seriesRepo, genreRepo)

                // ── Stub: AudibleApi returns a canned book ──────────────────────
                val audibleBook = canned_WayOfKings()
                val audibleApi = SingleBookFakeAudibleApi(audibleBook)

                // ── Stub: MockEngine-backed ImageStorage returns a tiny JPEG ─────
                val mockEngine =
                    MockEngine { _ ->
                        respond(
                            content = TINY_JPEG,
                            status = HttpStatusCode.OK,
                        )
                    }
                val imageStorage = ImageStorage(HttpClient(mockEngine))
                val coverImageStore =
                    CoverImageStore(ImageStore(Path(tempDir.resolve("covers").toString()), MAX_COVER_BYTES))

                // ── Wire MetadataService + MetadataLookupServiceImpl ────────────
                val cacheRepo = MetadataCacheRepository(sql, clock = FixedClock(TEST_NOW))
                val metadataService =
                    MetadataService(
                        audible = audibleApi,
                        itunes = NoOpITunesApi(),
                        cache = cacheRepo,
                    )
                val service =
                    buildService(
                        this,
                        bookRepo,
                        contributorRepo,
                        seriesRepo,
                        genreRepo,
                        imageStorage,
                        coverImageStore,
                        metadataService,
                        tempDir.toString(),
                    )

                // ── Act ──────────────────────────────────────────────────────────
                val bookId = "b2a-test-book"
                runTest {
                    // Seed a minimal book (no description, no ASIN, no cover)
                    bookRepo.upsert(minimalBook(bookId), clientOpId = null)

                    val result = service.applyBookMetadata(BookId(bookId), TEST_ASIN, MetadataLocale("us"), APPLY_SELECTION)

                    // ── Assert: AppResult.Success ────────────────────────────────
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // ── Assert: server-side book row is enriched ─────────────────
                    val enriched = bookRepo.findById(BookId(bookId))
                    enriched.shouldNotBeNull()
                    enriched.description shouldBe "Roshar is a world of stone and storms."
                    enriched.asin shouldBe TEST_ASIN
                    enriched.publisher shouldBe "Macmillan Audio"
                    enriched.language shouldBe "english"

                    // Author resolved via resolveOrCreate (ASIN "A123" is selectable).
                    // Narrator "Michael Kramer" has blank ASIN → null after provider mapping →
                    // not selectable; narratorAsins = emptySet() leaves narrator role untouched.
                    val authorRef = enriched.contributors.find { it.role == "author" }
                    authorRef.shouldNotBeNull()
                    authorRef.name shouldBe "Brandon Sanderson"

                    // Image downloaded once
                    mockEngine.requestHistory.size shouldBe 1

                    // ── Assert: cover stored via CoverImageStore + columns set ────
                    val cover = enriched.cover
                    cover.shouldNotBeNull()
                    cover.source shouldBe CoverSource.UPLOADED
                    cover.hash.shouldNotBeNull()
                }
            }
        }

        test("applyBookMetadata overwrites any existing cover — wizard pick is an explicit UPLOADED overwrite") {
            withSqlDatabase {
                val tempDir = Files.createTempDirectory("b2a-e2e-overwrite-").also { it.toFile().deleteOnExit() }
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()

                val contributorRepo = ContributorRepository(sql, bus, syncRegistry)
                val seriesRepo = SeriesRepository(sql, bus, syncRegistry)
                val genreRepo = GenreRepository(sql, bus, syncRegistry)
                val bookRepo =
                    BookRepository(sql, bus, syncRegistry, driver, contributorRepo, seriesRepo, genreRepo)

                val mockEngine = MockEngine { _ -> respond(TINY_JPEG, HttpStatusCode.OK) }
                val imageStorage = ImageStorage(HttpClient(mockEngine))
                val coverImageStore =
                    CoverImageStore(ImageStore(Path(tempDir.resolve("covers").toString()), MAX_COVER_BYTES))

                val cacheRepo = MetadataCacheRepository(sql, clock = FixedClock(TEST_NOW))
                val metadataService =
                    MetadataService(
                        audible = SingleBookFakeAudibleApi(canned_WayOfKings()),
                        itunes = NoOpITunesApi(),
                        cache = cacheRepo,
                    )
                val service =
                    buildService(
                        this,
                        bookRepo,
                        contributorRepo,
                        seriesRepo,
                        genreRepo,
                        imageStorage,
                        coverImageStore,
                        metadataService,
                        tempDir.toString(),
                    )

                val bookId = "b2a-overwrite"
                runTest {
                    // Seed a book with a pre-existing EMBEDDED cover to prove the gate is gone
                    val existingHash = "embedded-sha256-hash"
                    bookRepo.upsert(
                        minimalBook(bookId).copy(
                            cover = CoverPayload(source = CoverSource.EMBEDDED, hash = existingHash),
                        ),
                        clientOpId = null,
                    )
                    bookRepo.setManagedCover(BookId(bookId), "covers/$bookId.png", existingHash, CoverSource.EMBEDDED)

                    val result = service.applyBookMetadata(BookId(bookId), TEST_ASIN, MetadataLocale("us"), APPLY_SELECTION)

                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // Wizard cover is an explicit user choice: source must be UPLOADED, replacing the EMBEDDED one
                    val afterApply = bookRepo.findById(BookId(bookId))
                    afterApply.shouldNotBeNull()
                    afterApply.cover?.source shouldBe CoverSource.UPLOADED
                }
            }
        }

        test("applyBookMetadata returns MetadataError.NotFound when book is absent") {
            withSqlDatabase {
                val tempDir = Files.createTempDirectory("b2a-e2e-notfound-").also { it.toFile().deleteOnExit() }
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()

                val contributorRepo = ContributorRepository(sql, bus, syncRegistry)
                val seriesRepo = SeriesRepository(sql, bus, syncRegistry)
                val genreRepo = GenreRepository(sql, bus, syncRegistry)
                val bookRepo =
                    BookRepository(sql, bus, syncRegistry, driver, contributorRepo, seriesRepo, genreRepo)

                val cacheRepo = MetadataCacheRepository(sql, clock = FixedClock(TEST_NOW))
                val metadataService =
                    MetadataService(
                        audible = SingleBookFakeAudibleApi(null),
                        itunes = NoOpITunesApi(),
                        cache = cacheRepo,
                    )
                val imageStorage =
                    ImageStorage(HttpClient(MockEngine { _ -> respond("", HttpStatusCode.OK) }))
                val coverImageStore =
                    CoverImageStore(ImageStore(Path(tempDir.resolve("covers").toString()), MAX_COVER_BYTES))
                val service =
                    buildService(
                        this,
                        bookRepo,
                        contributorRepo,
                        seriesRepo,
                        genreRepo,
                        imageStorage,
                        coverImageStore,
                        metadataService,
                        tempDir.toString(),
                    )

                runTest {
                    val result = service.applyBookMetadata(BookId("no-such-book"), TEST_ASIN, MetadataLocale("us"), APPLY_SELECTION)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }
    })

// ─── Test helpers ──────────────────────────────────────────────────────────────

private const val MAX_COVER_BYTES = 10L * 1024 * 1024

// canned_WayOfKings() has author ASIN "A123" and narrator ASIN "" (blank → null after the
// composed-book wire mapping). Series is empty. Only the author is
// selectable by ASIN; the narrator's null ASIN makes it unselectable per the design.
private val APPLY_SELECTION =
    MetadataApplySelection(
        title = true,
        subtitle = true,
        description = true,
        publisher = true,
        releaseDate = true,
        language = true,
        cover = true,
        authorAsins = setOf("A123"),
        narratorAsins = emptySet(),
        seriesAsins = emptySet(),
    )

private fun buildService(
    dbs: SqlTestDatabases,
    bookRepo: BookRepository,
    contributorRepo: ContributorRepository,
    seriesRepo: SeriesRepository,
    genreRepo: GenreRepository,
    imageStorage: ImageStorage,
    coverImageStore: CoverImageStore,
    metadataService: MetadataService,
    tempDir: String,
): MetadataLookupServiceImpl =
    MetadataLookupServiceImpl(
        metadataService = metadataService,
        coordinator = testCoordinator(metadataService),
        coverSearchService =
            CoverSearchService(
                readBook = { null },
                registry = MetadataProviderRegistry(emptyList()),
                probeDimensions = { null },
            ),
        bookRepository = bookRepo,
        contributorRepository = contributorRepo,
        seriesRepository = seriesRepo,
        imageDeps =
            MetadataImageDeps(
                imageStorage = imageStorage,
                coverImageStore = coverImageStore,
                imageHome = Path(tempDir),
            ),
        enrichmentDeps = testEnrichmentDeps(dbs.sql, ChangeBus(), SyncRegistry()),
        permissionPolicy = UserPermissionPolicy(dbs.sql),
        sqlDb = dbs.sql,
        genreRepository = genreRepo,
        principal = PrincipalProvider { UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT) },
    )

private fun minimalBook(id: String): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "The Way of Kings (untagged)",
        sortTitle = null,
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
        totalDuration = 0L,
        cover = null,
        rootRelPath = "test/way-of-kings",
        inode = null,
        scannedAt = 0L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles = emptyList(),
        chapters = emptyList(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

private fun canned_WayOfKings(): AudibleBook =
    AudibleBook(
        asin = TEST_ASIN,
        title = "The Way of Kings",
        subtitle = "The Stormlight Archive, Book One",
        authors = listOf(AudibleContributor(asin = "A123", name = "Brandon Sanderson")),
        narrators = listOf(AudibleContributor(asin = "", name = "Michael Kramer")),
        publisher = "Macmillan Audio",
        releaseDate = "2010-08-31",
        runtimeMinutes = 2761,
        description = "Roshar is a world of stone and storms.",
        coverUrl = "https://example.com/way-of-kings.jpg",
        series = emptyList(),
        genres = listOf("Fantasy"),
        language = "english",
        rating = 4.9f,
        ratingCount = 50_000,
    )

/**
 * Fake [AudibleApi] that returns a single pre-configured book on `getBook`.
 * All other methods return empty / null.
 */
private class SingleBookFakeAudibleApi(
    private val book: AudibleBook?,
) : AudibleApi {
    // Surface the same book in search so the cover source (AudibleProvider.searchCovers) finds its cover.
    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> =
        AppResult.Success(
            book
                ?.let {
                    listOf(
                        AudibleSearchResult(
                            asin = it.asin,
                            title = it.title,
                            subtitle = it.subtitle,
                            authors = it.authors,
                            narrators = it.narrators,
                            coverUrl = it.coverUrl,
                            runtimeMinutes = it.runtimeMinutes,
                            releaseDate = it.releaseDate,
                        ),
                    )
                }.orEmpty(),
        )

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

/** Stub [ITunesApi] that never finds a cover. */
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
