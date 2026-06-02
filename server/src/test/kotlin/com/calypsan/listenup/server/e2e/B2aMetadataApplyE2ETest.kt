@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.api.MetadataLookupServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributor
import com.calypsan.listenup.server.metadata.audible.AudibleContributorProfile
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
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

/**
 * Tier 2 E2E test for the Books-B2a metadata-apply path.
 *
 * Exercises the full `MetadataLookupServiceImpl.applyBookMetadata` call
 * chain with:
 *  - A stubbed [AudibleApi] returning a canned [AudibleBook].
 *  - A [MockEngine]-backed [ImageStorage] that records the download URL
 *    and writes a tiny PNG to disk.
 *  - Real [BookRepository], [ContributorRepository], and [SeriesRepository]
 *    against an in-memory SQLite database.
 *  - Real [MetadataCacheRepository] and [MetadataService] so caching
 *    semantics are exercised (not bypassed).
 *
 * After `applyBookMetadata` returns [AppResult.Success], the test asserts
 * the server-side [BookRepository] row carries the Audible metadata —
 * specifically `description` populated and `asin` stamped. These enrichments
 * flow to clients via the SSE sync stream; the sync round-trip is covered by
 * the existing [com.calypsan.listenup.client.books.BooksEndToEndTest] suite.
 *
 * This test lives in `server/src/test/` (not `sharedLogic/src/jvmTest/`)
 * because [MetadataLookupServiceImpl] is server-only and the full in-process
 * cross-stack test would require extending `withClientSyncEngineAgainstServer`
 * with metadata module wiring — deferred to Books-B2b when the metadata UI
 * surface lands.
 */
class B2aMetadataApplyE2ETest :
    FunSpec({

        test("applyBookMetadata enriches the server book row and returns Success") {
            withInMemoryDatabase {
                val tempDir = Files.createTempDirectory("b2a-e2e-").toString()
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()

                // ── Repositories ────────────────────────────────────────────────
                val contributorRepo = ContributorRepository(db, bus, syncRegistry)
                val seriesRepo = SeriesRepository(db, bus, syncRegistry)
                val bookRepo =
                    BookRepository(db, bus, syncRegistry, contributorRepo, seriesRepo)

                // ── Stub: AudibleApi returns a canned book ──────────────────────
                val audibleBook =
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
                val audibleApi = SingleBookFakeAudibleApi(audibleBook)

                // ── Stub: MockEngine-backed ImageStorage writes a tiny file ─────
                val mockEngine =
                    MockEngine { _ ->
                        respond(
                            content = byteArrayOf(0xFF.toByte(), 0xD8.toByte()), // tiny JPEG magic
                            status = HttpStatusCode.OK,
                        )
                    }
                val imageStorage = ImageStorage(HttpClient(mockEngine))

                // ── Wire MetadataService + MetadataLookupServiceImpl ────────────
                val cacheRepo = MetadataCacheRepository(db, clock = FixedClock(TEST_NOW))
                val metadataService =
                    MetadataService(
                        audible = audibleApi,
                        itunes = NoOpITunesApi(),
                        cache = cacheRepo,
                    )
                val service =
                    MetadataLookupServiceImpl(
                        metadataService = metadataService,
                        bookRepository = bookRepo,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                        imageStorage = imageStorage,
                        imageHome = Path(tempDir),
                        permissionPolicy = UserPermissionPolicy(db),
                        principal =
                            PrincipalProvider {
                                UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT)
                            },
                    )

                // ── Act ──────────────────────────────────────────────────────────
                val bookId = "b2a-test-book"
                runTest {
                    // Seed a minimal book (no description, no ASIN)
                    bookRepo.upsert(minimalBook(bookId), clientOpId = null)

                    val result = service.applyBookMetadata(BookId(bookId), TEST_ASIN, AudibleRegion.US)

                    // ── Assert: AppResult.Success ────────────────────────────────
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    // ── Assert: server-side book row is enriched ─────────────────
                    val enriched = bookRepo.findById(BookId(bookId))
                    enriched.shouldNotBeNull()
                    enriched.description shouldBe "Roshar is a world of stone and storms."
                    enriched.asin shouldBe TEST_ASIN
                    enriched.publisher shouldBe "Macmillan Audio"
                    enriched.language shouldBe "english"

                    // Author and narrator contributors resolved via resolveOrCreate
                    val authorRef = enriched.contributors.find { it.role == "author" }
                    authorRef.shouldNotBeNull()
                    authorRef.name shouldBe "Brandon Sanderson"
                    val narratorRef = enriched.contributors.find { it.role == "narrator" }
                    narratorRef.shouldNotBeNull()
                    narratorRef.name shouldBe "Michael Kramer"

                    // Image downloaded once to the cover path
                    mockEngine.requestHistory.size shouldBe 1
                }
            }
        }

        test("applyBookMetadata returns MetadataError.NotFound when book is absent") {
            withInMemoryDatabase {
                val tempDir = Files.createTempDirectory("b2a-e2e-notfound-").toString()
                val db = this
                seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()

                val contributorRepo = ContributorRepository(db, bus, syncRegistry)
                val seriesRepo = SeriesRepository(db, bus, syncRegistry)
                val bookRepo =
                    BookRepository(db, bus, syncRegistry, contributorRepo, seriesRepo)

                val cacheRepo = MetadataCacheRepository(db, clock = FixedClock(TEST_NOW))
                val metadataService =
                    MetadataService(
                        audible = SingleBookFakeAudibleApi(null),
                        itunes = NoOpITunesApi(),
                        cache = cacheRepo,
                    )
                val service =
                    MetadataLookupServiceImpl(
                        metadataService = metadataService,
                        bookRepository = bookRepo,
                        contributorRepository = contributorRepo,
                        seriesRepository = seriesRepo,
                        imageStorage = ImageStorage(HttpClient(MockEngine { _ -> respond("", HttpStatusCode.OK) })),
                        imageHome = Path(tempDir),
                        permissionPolicy = UserPermissionPolicy(db),
                        principal =
                            PrincipalProvider {
                                UserPrincipal(UserId("test-admin"), SessionId("s"), UserRole.ROOT)
                            },
                    )

                runTest {
                    val result = service.applyBookMetadata(BookId("no-such-book"), TEST_ASIN, AudibleRegion.US)
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }
    })

// ─── Test helpers ──────────────────────────────────────────────────────────────

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

/**
 * Fake [AudibleApi] that returns a single pre-configured book on `getBook`.
 * All other methods return empty / null.
 */
private class SingleBookFakeAudibleApi(
    private val book: AudibleBook?,
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
}

/** Stub [ITunesApi] that never finds a cover. */
private class NoOpITunesApi : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)
}
