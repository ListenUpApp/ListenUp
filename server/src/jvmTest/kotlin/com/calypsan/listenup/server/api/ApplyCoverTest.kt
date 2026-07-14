@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.services.CoverSearchService
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.ProductTag
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.testCoordinator
import com.calypsan.listenup.server.testing.testEnrichmentDeps
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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

// Minimal valid 1×1 PNG (passes ImageStore's magic-number sniff).
private val ONE_PX_PNG: ByteArray =
    java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
    )

private const val MAX_COVER_BYTES = 10L * 1024 * 1024

class ApplyCoverTest :
    FunSpec({
        test("applyCover stores the downloaded bytes and marks the cover UPLOADED") {
            withCoverFixture(downloadBytes = ONE_PX_PNG) { service, books ->
                val result = service.applyCover(BookId("book1"), "https://itunes/any.png")
                result.shouldBeInstanceOf<AppResult.Success<*>>()

                val saved = books.findById(BookId("book1"))
                saved.shouldNotBeNull()
                saved.cover?.source shouldBe CoverSource.UPLOADED
            }
        }

        test("applyCover rejects non-image bytes as a non-retryable Malformed error") {
            withCoverFixture(downloadBytes = "not an image".toByteArray()) { service, books ->
                val result = service.applyCover(BookId("book1"), "https://itunes/not-image.txt")

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.Malformed>()
                failure.error.isRetryable shouldBe false

                // No cover written and no orphan stored.
                books.findById(BookId("book1"))?.cover.shouldBeNull()
            }
        }

        test("applyCover returns NotFound for an unknown book without downloading or storing") {
            withCoverFixture(downloadBytes = ONE_PX_PNG) { service, _ ->
                val result = service.applyCover(BookId("does-not-exist"), "https://itunes/any.png")

                val failure = result.shouldBeInstanceOf<AppResult.Failure>()
                failure.error.shouldBeInstanceOf<MetadataError.NotFound>()
            }
        }
    })

/**
 * Spins up an in-memory DB seeded with one book ("book1"), a cover-scoped [ImageStore] over a
 * temp dir, and a [MetadataLookupServiceImpl] whose [ImageStorage] serves [downloadBytes] for any
 * URL. Runs [block] inside `runTest` with the wired service and book repository.
 */
private fun withCoverFixture(
    downloadBytes: ByteArray,
    block: suspend (service: MetadataLookupServiceImpl, books: BookRepository) -> Unit,
) {
    withSqlDatabase {
        val db = this
        val tempDir = Files.createTempDirectory("applycover-").also { it.toFile().deleteOnExit() }
        sql.seedTestLibraryAndFolder()

        val bus = ChangeBus()
        val registry = SyncRegistry()
        val contributorRepo = ContributorRepository(db.sql, bus, registry)
        val seriesRepo = SeriesRepository(db.sql, bus, registry)
        val genreRepo = GenreRepository(db.sql, bus, registry)
        val books = BookRepository(db.sql, bus, registry, db.driver, contributorRepo, seriesRepo, genreRepo)

        runTest {
            books
                .upsert(coverBookFixture("book1"), clientOpId = null)
                .shouldBeInstanceOf<AppResult.Success<*>>()

            val coverStore = CoverImageStore(ImageStore(Path(tempDir.resolve("covers").toString()), MAX_COVER_BYTES))
            val mockHttp =
                HttpClient(
                    MockEngine {
                        respond(
                            content = downloadBytes,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Image.PNG.toString()),
                        )
                    },
                )
            val metadataService =
                MetadataService(
                    audible = ApplyCoverNoOpAudible(),
                    itunes = ApplyCoverNoOpITunes(),
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
                            imageStorage = ImageStorage(httpClient = mockHttp),
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

            block(service, books)
        }
    }
}

private fun coverBookFixture(id: String): BookSyncPayload =
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

private class ApplyCoverNoOpAudible : AudibleApi {
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

private class ApplyCoverNoOpITunes : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)

    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = AppResult.Success(emptyList())
}
