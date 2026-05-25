@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MetadataContributorHit
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.metadata.AudibleRegion
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleBook
import com.calypsan.listenup.server.metadata.audible.AudibleChapter
import com.calypsan.listenup.server.metadata.audible.AudibleContributorProfile
import com.calypsan.listenup.server.metadata.audible.AudibleSearchResult
import com.calypsan.listenup.server.metadata.audible.SearchParams
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesCoverHit
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import org.jetbrains.exposed.v1.jdbc.Database
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
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
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun makeService(
    audible: AudibleApi,
    db: Database,
): MetadataLookupServiceImpl {
    val tempDir = Files.createTempDirectory("metadata-test-").toString()
    val metadataService =
        MetadataService(
            audible = audible,
            itunes = NoOpITunesApi(),
            cache = MetadataCacheRepository(db, clock = FixedClock(NOW)),
        )
    val bus = ChangeBus()
    val syncRegistry = SyncRegistry()
    val contributorRepo = ContributorRepository(db, bus, syncRegistry)
    val seriesRepo = SeriesRepository(db, bus, syncRegistry)
    return MetadataLookupServiceImpl(
        metadataService = metadataService,
        bookRepository =
            BookRepository(
                db = db,
                bus = bus,
                registry = syncRegistry,
                _libraryRegistry = LibraryRegistry(db, mapOf("LISTENUP_LIBRARY_PATH" to tempDir)),
                contributorRepository = contributorRepo,
                seriesRepository = seriesRepo,
            ),
        contributorRepository = contributorRepo,
        seriesRepository = seriesRepo,
        imageStorage = ImageStorage(HttpClient(MockEngine { _ -> respond("", HttpStatusCode.OK) })),
        libraryPath = Path(tempDir),
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
}

private class NoOpITunesApi : ITunesApi {
    override suspend fun findCover(
        title: String,
        author: String,
    ): AppResult<ITunesCoverHit?> = AppResult.Success(null)
}
