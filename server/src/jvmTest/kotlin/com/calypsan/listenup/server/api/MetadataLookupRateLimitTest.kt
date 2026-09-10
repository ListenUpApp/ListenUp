@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.auth.MetadataRateBucket
import com.calypsan.listenup.server.auth.MetadataRateLimiter
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleRegion
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
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.rootPrincipal
import com.calypsan.listenup.server.testing.testCoordinator
import com.calypsan.listenup.server.testing.testEnrichmentDeps
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.time.Instant

/**
 * Pins the per-**user** throttle and the query bound on the metadata lookup reads.
 *
 * The five read methods are open to any authenticated user, and the provider limiters behind them
 * (`AudibleClient`'s `rateLimiter.await`) are process-wide and *blocking* — they queue rather than
 * reject. So without a per-user bucket, one member's burst head-of-line-blocks every other caller,
 * including an admin running the match wizard. That is the failure this bounds.
 *
 * **Why a burst rather than "the 21st call"** — `RateLimitTest`'s reasoning: the limiter is a token
 * bucket that refills continuously, so a "call capacity+1 fails" assertion quietly requires every
 * preceding call to land inside one refill interval, and fails on a slow runner. These specs pin a
 * fixed clock as well, which removes refill entirely; the burst is what keeps the assertion honest
 * if that ever changes.
 */
class MetadataLookupRateLimitTest :
    FunSpec({

        test("a searchBooks burst from one user is throttled") {
            withSqlDatabase {
                runTest {
                    val service = rateLimitedService().copyWith(rootPrincipal("user-a"))

                    val outcomes = (1..SEARCH_BURST).map { service.searchBooks("stormlight", null, null) }

                    outcomes
                        .filterIsInstance<AppResult.Failure>()
                        .map { it.error }
                        .filterIsInstance<AuthError.RateLimited>()
                        .shouldNotBeEmpty()

                    // The control: the first `capacity` calls start against a full bucket, so a
                    // limiter that rejected everything would not satisfy the assertion above.
                    outcomes.take(MetadataRateBucket.SEARCH.perMinuteLimit).forEach {
                        it.shouldBeInstanceOf<AppResult.Success<*>>()
                    }
                }
            }
        }

        test("a second user is unaffected by the first user's burst") {
            withSqlDatabase {
                runTest {
                    // ONE limiter shared by both per-request copies, exactly as production wires it.
                    val limiter = MetadataRateLimiter(FixedClock(NOW))
                    val shared = rateLimitedService(limiter)

                    val userA = shared.copyWith(rootPrincipal("user-a"))
                    repeat(SEARCH_BURST) { userA.searchBooks("stormlight", null, null) }
                    userA.searchBooks("stormlight", null, null).shouldBeInstanceOf<AppResult.Failure>()

                    // The key is the user, not the process: user B's bucket is untouched.
                    val userB = shared.copyWith(rootPrincipal("user-b"))
                    userB.searchBooks("stormlight", null, null).shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }

        test("a query past the length bound is rejected before any provider is consulted") {
            withSqlDatabase {
                runTest {
                    val audible = CountingAudibleApi()
                    val service = rateLimitedService(audible = audible).copyWith(rootPrincipal("user-a"))

                    val result = service.searchBooks("q".repeat(OVER_LONG_QUERY_CHARS), null, null)

                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<MetadataError.Malformed>()
                    // The point of clamping at the service boundary: no provider call, and no slot
                    // in the process-wide provider limiter, is spent on a string that is not a query.
                    audible.searches shouldBe 0
                }
            }
        }

        test("a blank query is rejected before any provider is consulted") {
            withSqlDatabase {
                runTest {
                    val audible = CountingAudibleApi()
                    val service = rateLimitedService(audible = audible).copyWith(rootPrincipal("user-a"))

                    service
                        .searchBooks("   ", null, null)
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<MetadataError.Malformed>()
                    audible.searches shouldBe 0
                }
            }
        }
    })

/** Calls fired in the burst — twice `SEARCH`'s capacity, the `RateLimitTest` margin. */
private const val SEARCH_BURST = 40

/** Comfortably past `MAX_METADATA_QUERY_LENGTH`, without encoding that constant here. */
private const val OVER_LONG_QUERY_CHARS = 1_000

private val NOW = Instant.fromEpochMilliseconds(1_730_000_000_000L)

/** An [AudibleApi] that records how many searches actually reached a provider. */
private class CountingAudibleApi : AudibleApi {
    var searches = 0
        private set

    override suspend fun search(
        region: AudibleRegion,
        params: SearchParams,
    ): AppResult<List<AudibleSearchResult>> {
        searches++
        return AppResult.Success(emptyList())
    }

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

/** An [ITunesApi] with nothing to say — cover composition is not what these specs are about. */
private class NoCoversITunesApi : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)

    override suspend fun searchCovers(
        title: String,
        author: String,
    ): AppResult<List<ITunesCoverHit>> = AppResult.Success(emptyList())
}

/** The service under test, wired with a real [MetadataRateLimiter] over a fixed clock. */
private fun SqlTestDatabases.rateLimitedService(
    limiter: MetadataRateLimiter = MetadataRateLimiter(FixedClock(NOW)),
    audible: AudibleApi = CountingAudibleApi(),
): MetadataLookupServiceImpl {
    val tempDir = Files.createTempDirectory("metadata-ratelimit-test-").toAbsolutePath()
    val metadataService =
        MetadataService(
            audible = audible,
            itunes = NoCoversITunesApi(),
            cache = MetadataCacheRepository(sql, clock = FixedClock(NOW)),
        )
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(sql, bus, syncRegistry)
    val seriesRepo = SeriesRepository(sql, bus, syncRegistry)
    val genreRepo = GenreRepository(sql, bus, syncRegistry)
    return MetadataLookupServiceImpl(
        metadataService = metadataService,
        coordinator = testCoordinator(metadataService),
        coverSearchService =
            CoverSearchService(
                readBook = { null },
                registry = MetadataProviderRegistry(emptyList()),
                probeDimensions = { null },
            ),
        bookRepository =
            BookRepository(
                db = sql,
                driver = driver,
                bus = bus,
                registry = syncRegistry,
                contributorRepository = contributorRepo,
                seriesRepository = seriesRepo,
                genreRepository = genreRepo,
            ),
        contributorRepository = contributorRepo,
        seriesRepository = seriesRepo,
        imageDeps =
            MetadataImageDeps(
                imageStorage = ImageStorage(HttpClient(MockEngine { respond("", HttpStatusCode.OK) })),
                coverImageStore =
                    CoverImageStore(
                        ImageStore(Path(tempDir.resolve("covers").toString()), maxBytes = 10L * 1024 * 1024),
                    ),
                imageHome = Path(tempDir.toString()),
            ),
        enrichmentDeps = testEnrichmentDeps(sql, driver, bus, syncRegistry),
        permissionPolicy = UserPermissionPolicy(sql),
        sqlDb = sql,
        genreRepository = genreRepo,
        rateLimiter = limiter,
        bookAccessPolicy = BookAccessPolicy(sql, driver),
    )
}
