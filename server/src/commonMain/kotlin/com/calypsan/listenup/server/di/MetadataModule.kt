package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.server.api.MetadataEnrichmentDeps
import com.calypsan.listenup.server.api.MetadataImageDeps
import com.calypsan.listenup.server.api.MetadataLookupServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.cover.CoverImageStore
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.io.readEnv
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.metadata.EnrichmentCoordinator
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleClient
import com.calypsan.listenup.server.metadata.audible.AudibleRateLimiter
import com.calypsan.listenup.server.metadata.audnexus.AudnexusApi
import com.calypsan.listenup.server.metadata.audnexus.AudnexusClient
import com.calypsan.listenup.server.metadata.audnexus.AudnexusRateLimiter
import com.calypsan.listenup.server.metadata.custom.CustomHttpProvider
import com.calypsan.listenup.server.metadata.custom.CustomMetadataClient
import com.calypsan.listenup.server.metadata.custom.CustomProviderSpec
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesClient
import com.calypsan.listenup.server.metadata.itunes.ITunesRateLimiter
import com.calypsan.listenup.server.metadata.itunes.ImageDimensionProbe
import com.calypsan.listenup.server.metadata.provider.AudibleProvider
import com.calypsan.listenup.server.metadata.provider.AudnexusProvider
import com.calypsan.listenup.server.metadata.provider.ITunesProvider
import com.calypsan.listenup.server.metadata.spi.EnrichmentRoutes
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
import org.koin.core.scope.Scope
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
 *  - [AudibleProvider] / [AudnexusProvider] / [ITunesProvider] — the capability-SPI providers,
 *    collected in a [MetadataProviderRegistry]; [CoverSearchService] fans cover lookups over the
 *    registry's [com.calypsan.listenup.server.metadata.spi.CoverSource]s. Audnexus is the contributor-
 *    profile, catalog-chapter, and genre/tag source. Any operator-declared
 *    [com.calypsan.listenup.server.metadata.custom.CustomHttpProvider]s (from `LISTENUP_CUSTOM_PROVIDERS`)
 *    join the same registry — the extensibility seam, and the only per-book character source.
 *  - [EnrichmentRoutes] / [EnrichmentCoordinator] — the operator-configured provider precedence
 *    and the composer that walks it per domain to build a book's metadata for the lookup service.
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

private val metadataModuleLogger = loggerFor<EnrichmentRoutes>()

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

        single { AudnexusRateLimiter() }

        single<AudnexusApi> { audnexusApi() }

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

        single { AudnexusProvider(client = get(), cache = get()) }

        single { ITunesProvider(itunes = get()) }

        single {
            MetadataProviderRegistry(
                providers =
                    listOf(get<AudibleProvider>(), get<AudnexusProvider>(), get<ITunesProvider>()) + customProviders(),
            )
        }

        single {
            EnrichmentRoutes.parse(
                order = readEnv("LISTENUP_ENRICHMENT_ORDER"),
                routes = readEnv("LISTENUP_ENRICHMENT_ROUTES"),
            )
        }

        single { enrichmentCoordinator() }

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
                coordinator = get(),
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
                probeDimensions = { url -> get<ImageDimensionProbe>().probe(url) },
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
 * Mood/trope enrichment bindings for the metadata-apply path:
 *  - [MetadataEnrichmentDeps] — bundles the add-only [BookMoodWriter] / [BookTagWriter] junction
 *    writers that [com.calypsan.listenup.server.api.BookMetadataApplier] uses to reconcile a book's
 *    moods/tropes to the user's apply selection.
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
        )
    }
}

/**
 * Builds an operator's custom metadata providers from `LISTENUP_CUSTOM_PROVIDERS`, one
 * [CustomHttpProvider] per parsed [CustomProviderSpec], each over the shared metadata HTTP
 * client with its own rate limiter. Never-strand: a misconfigured env parses to an empty list
 * (see [CustomProviderSpec.parse]), so enrichment runs on the built-ins alone.
 */
private fun Scope.customProviders(): List<CustomHttpProvider> =
    CustomProviderSpec.parse(readEnv("LISTENUP_CUSTOM_PROVIDERS")).map { spec ->
        CustomHttpProvider(
            spec = spec,
            client =
                CustomMetadataClient(
                    httpClient = get(named(METADATA_HTTP_CLIENT)),
                    json =
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        },
                    baseUrl = spec.baseUrl,
                ),
        )
    }

/**
 * Builds the [EnrichmentCoordinator] over the registry and configured routes, warning at boot for any
 * route that names a `custom:<name>` provider with no matching declaration in `LISTENUP_CUSTOM_PROVIDERS`
 * (a typo silently resolves to nothing) — a registry-aware check the parser can't do on its own. Never
 * fails: a mis-routed custom token is a warning, not a boot error.
 */
private fun Scope.enrichmentCoordinator(): EnrichmentCoordinator {
    val routes = get<EnrichmentRoutes>()
    val registry = get<MetadataProviderRegistry>()
    routes.unresolvedCustomProviders(registry.byId.keys).forEach { id ->
        metadataModuleLogger.warn {
            "Enrichment route names custom provider '${id.value}', but no such provider is " +
                "declared in LISTENUP_CUSTOM_PROVIDERS — routes to it resolve to nothing."
        }
    }
    return EnrichmentCoordinator(registry = registry, routes = routes)
}

private fun Scope.audnexusApi(): AudnexusApi =
    AudnexusClient(
        httpClient = get(named(METADATA_HTTP_CLIENT)),
        json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            },
        rateLimiter = get(),
        baseUrl = readEnv("LISTENUP_AUDNEXUS_URL")?.takeIf { it.isNotBlank() } ?: AudnexusClient.DEFAULT_BASE_URL,
    )
