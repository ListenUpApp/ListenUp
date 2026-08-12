@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.CoverSearchService
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.testCoordinator
import com.calypsan.listenup.server.testing.testEnrichmentDeps
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path

private const val TEST_MAX_BYTES = 64L

/**
 * Security-focused tests for [MetadataLookupServiceImpl.applyCover] — SEC-05 (SSRF / unbounded
 * download). Functional behaviour (happy path, image validation, unknown-book) lives in
 * [ApplyCoverTest]; this file covers the URL-safety boundary specifically: a rejected initial URL,
 * a redirect that targets a private host, an oversize response, and the requirement that failure
 * `debugInfo` never echoes a live exception message back to the caller.
 */
class MetadataLookupServiceApplyCoverTest :
    FunSpec({
        test("applyCover rejects a non-HTTPS URL without making any network request") {
            withApplyCoverFixture(engine = neverCalledEngine()) { service ->
                val result = service.applyCover(BookId("book1"), "http://example.com/cover.jpg")
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
            }
        }

        test("applyCover rejects a loopback-host URL without making any network request") {
            withApplyCoverFixture(engine = neverCalledEngine()) { service ->
                val result = service.applyCover(BookId("book1"), "https://127.0.0.1/cover.jpg")
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
            }
        }

        test("applyCover rejects a redirect that targets a link-local host, never following it") {
            val engine =
                MockEngine { request ->
                    if (request.url.host == "public.example.com") {
                        respond(
                            content = ByteArray(0),
                            status = HttpStatusCode.Found,
                            headers = headersOf(HttpHeaders.Location, "https://169.254.169.254/secret"),
                        )
                    } else {
                        error("must never follow the redirect to a private host: ${request.url}")
                    }
                }
            withApplyCoverFixture(engine = engine) { service ->
                val result = service.applyCover(BookId("book1"), "https://public.example.com/cover.jpg")
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.UnsafeUrl>()
            }
        }

        test("applyCover rejects an oversize response as a non-retryable Malformed error") {
            val oversized = ByteArray((TEST_MAX_BYTES * 4).toInt()) { 1 }
            val engine =
                MockEngine {
                    respond(
                        content = oversized,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Image.PNG.toString()),
                    )
                }
            withApplyCoverFixture(engine = engine, maxBytes = TEST_MAX_BYTES) { service ->
                val result = service.applyCover(BookId("book1"), "https://public.example.com/cover.jpg")
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.Malformed>()
                failure.error.isRetryable shouldBe false
            }
        }

        test("applyCover's failure debugInfo never echoes a live download-exception message") {
            val engine =
                MockEngine {
                    throw java.io.IOException("Connection refused: connect to 10.0.0.5:9200")
                }
            withApplyCoverFixture(engine = engine) { service ->
                val result = service.applyCover(BookId("book1"), "https://public.example.com/cover.jpg")
                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.ExternalUnavailable>()
                val debugInfo = failure.error.debugInfo
                debugInfo.shouldBeInstanceOf<String>()
                debugInfo shouldNotContain "10.0.0.5"
                debugInfo shouldNotContain "Connection refused"
            }
        }

        test("applyCover succeeds through a redirect that targets a public host") {
            val onePxPng =
                java.util.Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
                )
            val engine =
                MockEngine { request ->
                    if (request.url.host == "redirector.example.com") {
                        respond(
                            content = ByteArray(0),
                            status = HttpStatusCode.Found,
                            headers = headersOf(HttpHeaders.Location, "https://cdn.example.com/cover.png"),
                        )
                    } else {
                        respond(
                            content = onePxPng,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Image.PNG.toString()),
                        )
                    }
                }
            withApplyCoverFixture(engine = engine) { service ->
                val result = service.applyCover(BookId("book1"), "https://redirector.example.com/cover.jpg")
                result.shouldBeInstanceOf<AppResult.Success<*>>()
            }
        }
    })

/** An engine whose handler fails the test if ever invoked — proves no network call was made. */
private fun neverCalledEngine(): MockEngine = MockEngine { request -> error("must never make a network request for a rejected URL: ${request.url}") }

/**
 * Spins up an in-memory DB seeded with one book ("book1"), a cover-scoped [ImageStore] over a
 * temp dir, and a [MetadataLookupServiceImpl] wired to an [ImageStorage] backed by [engine] with
 * [maxBytes] as its download cap. Mirrors [ApplyCoverTest]'s `withCoverFixture`, but takes a full
 * [MockEngine] (rather than a fixed byte array) so redirect and oversize scenarios can be modeled.
 */
private fun withApplyCoverFixture(
    engine: MockEngine,
    maxBytes: Long = ImageStorage.DEFAULT_MAX_DOWNLOAD_BYTES,
    block: suspend (service: MetadataLookupServiceImpl) -> Unit,
) {
    withSqlDatabase {
        val db = this
        val tempDir = Files.createTempDirectory("applycover-security-").also { it.toFile().deleteOnExit() }
        sql.seedTestLibraryAndFolder()

        val bus = ChangeBus()
        val registry = SyncRegistry()
        val contributorRepo = ContributorRepository(db.sql, bus, registry)
        val seriesRepo = SeriesRepository(db.sql, bus, registry)
        val genreRepo = GenreRepository(db.sql, bus, registry)
        val books = BookRepository(db.sql, bus, registry, db.driver, contributorRepo, seriesRepo, genreRepo)

        runTest {
            books
                .upsert(securityFixtureBook("book1"), clientOpId = null)
                .shouldBeInstanceOf<AppResult.Success<*>>()

            val coverStore = CoverImageStore(ImageStore(Path(tempDir.resolve("covers").toString()), maxBytes))
            val metadataService =
                MetadataService(
                    audible = SecurityFixtureNoOpAudible(),
                    itunes = SecurityFixtureNoOpITunes(),
                    cache = MetadataCacheRepository(db.sql),
                )
            val service =
                MetadataLookupServiceImpl(
                    metadataService = metadataService,
                    coordinator = testCoordinator(metadataService),
                    coverSearchService =
                        CoverSearchService(
                            readBook = { null },
                            registry = MetadataProviderRegistry(emptyList()),
                            probeDimensions = { null },
                        ),
                    bookRepository = books,
                    contributorRepository = contributorRepo,
                    seriesRepository = seriesRepo,
                    imageDeps =
                        MetadataImageDeps(
                            imageStorage = ImageStorage(httpClient = HttpClient(engine), maxBytes = maxBytes),
                            coverImageStore = coverStore,
                            imageHome = Path(tempDir.toString()),
                        ),
                    enrichmentDeps = testEnrichmentDeps(db.sql, bus, registry),
                    permissionPolicy = UserPermissionPolicy(db.sql),
                    sqlDb = db.sql,
                    genreRepository = genreRepo,
                    principal =
                        PrincipalProvider {
                            UserPrincipal(UserId("root"), SessionId("s"), UserRole.ADMIN)
                        },
                )

            block(service)
        }
    }
}

private fun securityFixtureBook(id: String): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "The Way of Kings",
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

private class SecurityFixtureNoOpAudible : AudibleApi {
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

    override suspend fun getProductTags(
        region: AudibleRegion,
        asin: String,
    ): AppResult<List<ProductTag>> = AppResult.Success(emptyList())
}

private class SecurityFixtureNoOpITunes : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)

    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = AppResult.Success(emptyList())
}
