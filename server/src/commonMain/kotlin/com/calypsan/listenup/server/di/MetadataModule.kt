package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.server.api.MetadataEnrichmentDeps
import com.calypsan.listenup.server.api.MetadataImageDeps
import com.calypsan.listenup.server.api.MetadataLookupServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleClient
import com.calypsan.listenup.server.metadata.audible.AudibleRateLimiter
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesClient
import com.calypsan.listenup.server.metadata.itunes.ITunesRateLimiter
import com.calypsan.listenup.server.metadata.itunes.ImageDimensionProbe
import com.calypsan.listenup.server.metadata.provider.AudibleMetadataProvider
import com.calypsan.listenup.server.metadata.provider.AudibleProvider
import com.calypsan.listenup.server.metadata.provider.ITunesProvider
import com.calypsan.listenup.server.metadata.spi.MetadataProviderRegistry
import com.calypsan.listenup.server.scheduler.MetadataCacheCleanupTask
import com.calypsan.listenup.server.scheduler.OrphanImageCleanupTask
import com.calypsan.listenup.server.services.BookMoodWriter
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.BookSummary
import com.calypsan.listenup.server.services.BookTagWriter
import com.calypsan.listenup.server.services.CoverSearchService
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.TagRepository
import kotlin.time.Clock
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin module for the metadata enrichment slice. Wires:
 *
 *  - A dedicated [HttpClient] for external API calls (Audible + iTunes + image
 *    downloads). Separate from the Ktor server's internal client — these requests
 *    go to third-party hosts with different timeout and ContentNegotiation needs.
 *  - [AudibleRateLimiter] — shared rate limiter across all Audible requests.
 *  - [AudibleClient] / [ITunesClient] — thin adapters over the external APIs.
 *  - [MetadataCacheRepository] — SQLite-backed TTL cache for API responses.
 *  - [MetadataService] — orchestrator with region-aware fallback.
 *  - [AudibleProvider] / [ITunesProvider] — the capability-SPI providers, collected in a
 *    [MetadataProviderRegistry]; [CoverSearchService] fans cover lookups over the registry's
 *    [com.calypsan.listenup.server.metadata.spi.CoverSource]s. [AudibleMetadataProvider] still
 *    backs the un-migrated book/chapter lookup path until the enrichment coordinator lands.
 *  - [ImageStorage] — downloads cover/photo images to disk.
 *  - [MetadataLookupServiceImpl] — RPC implementation bound as [MetadataLookupService].
 *
 * Installed only when the books slice is active (`booksModule` is installed),
 * because [MetadataLookupServiceImpl] depends on [BookRepository] and friends
 * from that module. The module is installed unconditionally alongside `booksModule`
 * in `Application.kt`.
 *
 * [Json] used here is the lenient server JSON (ignores unknown keys) — the
 * internal Audible/iTunes wire format has fields not in our domain types.
 *
 * @param imageHome the always-available ListenUp home directory; downloaded cover
 *   images and contributor photos are written into per-type subdirectories under
 *   it (`covers/`, `contributors/`). Independent of the audio library path so it
 *   works on a library-less boot.
 */
private const val METADATA_HTTP_CLIENT = "metadataHttpClient"

fun metadataModule(imageHome: Path): Module =
    module {
        single(named(METADATA_HTTP_CLIENT)) {
            metadataHttpClient {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        },
                    )
                }
            }
        }

        single { AudibleRateLimiter() }

        single<AudibleApi> {
            AudibleClient(
                httpClient = get(named(METADATA_HTTP_CLIENT)),
                rateLimiter = get(),
                json =
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
            )
        }

        single { ITunesRateLimiter() }

        single<ITunesApi> {
            ITunesClient(
                httpClient = get(named(METADATA_HTTP_CLIENT)),
                json =
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                rateLimiter = get(),
            )
        }

        single { MetadataCacheRepository(db = get<ListenUpDatabase>()) }

        single {
            MetadataService(
                audible = get(),
                itunes = get(),
                cache = get(),
            )
        }

        single {
            ImageStorage(httpClient = get(named(METADATA_HTTP_CLIENT)))
        }

        single { ImageDimensionProbe(httpClient = get(named(METADATA_HTTP_CLIENT))) }

        single { AudibleProvider(metadataService = get()) }

        single { ITunesProvider(itunes = get()) }

        single { MetadataProviderRegistry(providers = listOf(get<AudibleProvider>(), get<ITunesProvider>())) }

        single { AudibleMetadataProvider(metadataService = get()) }

        single {
            val bookRepository = get<BookRepository>()
            val probe = get<ImageDimensionProbe>()
            CoverSearchService(
                readBook = { id ->
                    bookRepository.findById(id)?.let { b ->
                        BookSummary(
                            title = b.title,
                            author =
                                b.contributors.firstOrNull { it.role.equals("author", ignoreCase = true) }?.name
                                    ?: b.contributors.firstOrNull()?.name
                                    ?: "",
                        )
                    }
                },
                registry = get<MetadataProviderRegistry>(),
                probeDimensions = { url -> probe.probe(url) },
            )
        }

        metadataEnrichmentBindings()

        single<MetadataLookupService> {
            MetadataLookupServiceImpl(
                metadataService = get(),
                metadataProviders = listOf(get<AudibleMetadataProvider>()),
                coverSearchService = get(),
                bookRepository = get(),
                contributorRepository = get(),
                seriesRepository = get(),
                imageDeps =
                    MetadataImageDeps(
                        imageStorage = get(),
                        coverImageStore = get<CoverImageStore>(),
                        imageHome = imageHome,
                    ),
                enrichmentDeps = get<MetadataEnrichmentDeps>(),
                permissionPolicy = get<UserPermissionPolicy>(),
                sqlDb = get<ListenUpDatabase>(),
                genreRepository = get<GenreRepository>(),
                principal =
                    PrincipalProvider {
                        error("Unscoped MetadataLookupService — call copyWith(PrincipalProvider) at the route")
                    },
            )
        }

        metadataCleanupBindings(imageHome)
    }

/**
 * Scheduled-maintenance bindings for the metadata slice — the metadata-cache TTL sweep and the
 * orphan-image reaper. Split out of [metadataModule] to keep that module body under the length
 * budget.
 */
private fun Module.metadataCleanupBindings(imageHome: Path) {
    single { MetadataCacheCleanupTask(cache = get()) }
    single {
        OrphanImageCleanupTask(
            contributorRepository = get(),
            seriesRepository = get(),
            imageHome = imageHome,
        )
    }
}

/**
 * Audible mood/trope enrichment bindings for the metadata-apply path:
 *  - [MetadataEnrichmentDeps] — bundles the add-only [BookMoodWriter] / [BookTagWriter] junction
 *    writers and the best-effort [AudibleApi.getProductTags] scrape (empty list on failure) that
 *    [com.calypsan.listenup.server.api.BookMetadataApplier] consumes after a genre apply.
 *
 * [BookMoodWriter] is a shared single (it also backs the scanner); [BookTagWriter] is constructed
 * here from its repository deps, mirroring [BookRepository]'s inline construction. Split out of
 * [metadataModule] to keep that module body under the length budget.
 */
private fun Module.metadataEnrichmentBindings() {
    single {
        MetadataEnrichmentDeps(
            bookMoodWriter = get<BookMoodWriter>(),
            bookTagWriter =
                BookTagWriter(
                    clock = get<Clock>(),
                    tagRepository = get<TagRepository>(),
                    bookTagRepository = get<BookTagRepository>(),
                ),
            productTagSource = { region, asin ->
                get<AudibleApi>().getProductTags(region, asin).getOrElse { emptyList() }
            },
        )
    }
}
