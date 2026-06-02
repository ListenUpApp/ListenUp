package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.server.api.MetadataLookupServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.metadata.ImageStorage
import com.calypsan.listenup.server.metadata.audible.AudibleApi
import com.calypsan.listenup.server.metadata.audible.AudibleClient
import com.calypsan.listenup.server.metadata.audible.AudibleRateLimiter
import com.calypsan.listenup.server.metadata.itunes.ITunesApi
import com.calypsan.listenup.server.metadata.itunes.ITunesClient
import com.calypsan.listenup.server.scheduler.MetadataCacheCleanupTask
import com.calypsan.listenup.server.scheduler.OrphanImageCleanupTask
import com.calypsan.listenup.server.services.MetadataCacheRepository
import com.calypsan.listenup.server.services.MetadataService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
            HttpClient(CIO) {
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

        single<ITunesApi> {
            ITunesClient(
                httpClient = get(named(METADATA_HTTP_CLIENT)),
                json =
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
            )
        }

        single { MetadataCacheRepository(db = get()) }

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

        single<MetadataLookupService> {
            MetadataLookupServiceImpl(
                metadataService = get(),
                bookRepository = get(),
                contributorRepository = get(),
                seriesRepository = get(),
                imageStorage = get(),
                imageHome = imageHome,
                permissionPolicy = get<UserPermissionPolicy>(),
                principal =
                    PrincipalProvider {
                        error("Unscoped MetadataLookupService — call copyWith(PrincipalProvider) at the route")
                    },
            )
        }

        single { MetadataCacheCleanupTask(cache = get()) }

        single {
            OrphanImageCleanupTask(
                contributorRepository = get(),
                seriesRepository = get(),
                imageHome = imageHome,
            )
        }
    }
