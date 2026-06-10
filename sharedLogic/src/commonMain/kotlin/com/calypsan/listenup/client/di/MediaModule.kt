package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.ImageApi
import com.calypsan.listenup.client.data.remote.ImageApiContract
import com.calypsan.listenup.client.data.repository.AvatarDownloadRepositoryImpl
import com.calypsan.listenup.client.data.repository.CoverDownloadRepositoryImpl
import com.calypsan.listenup.client.data.repository.DownloadRepositoryImpl
import com.calypsan.listenup.client.data.repository.ImageRepositoryImpl
import com.calypsan.listenup.client.data.sync.CoverDownloadWorker
import com.calypsan.listenup.client.data.sync.ImageDownloader
import com.calypsan.listenup.client.data.sync.ImageDownloaderContract
import com.calypsan.listenup.client.domain.repository.AvatarDownloadRepository
import com.calypsan.listenup.client.domain.repository.CoverDownloadRepository
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** Koin qualifier for the application-lifetime [kotlinx.coroutines.CoroutineScope]. */
private const val APP_SCOPE = "appScope"

/**
 * Media aggregate Koin wiring — image API, image/cover/avatar download, and the
 * persistent cover download worker.
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageStorage] — platform storage module
 *  - [kotlinx.coroutines.CoroutineScope] named `appScope` — `appCoreModule`
 *  - [com.calypsan.listenup.client.data.local.db.DownloadDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.CoverDownloadDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.BookDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.domain.repository.BookRepository] — `bookModule`
 *  - [com.calypsan.listenup.client.download.DownloadEnqueuer] — platform download module
 */
val mediaModule: Module =
    module {
        // Image API for downloading cover images and uploading images
        single {
            ImageApi(clientFactory = get(), serverConfig = get())
        } bind ImageApiContract::class

        // Image downloader for batch cover downloads during sync
        single {
            ImageDownloader(
                imageApi = get(),
                imageStorage = get(),
            )
        } bind ImageDownloaderContract::class

        // ImageRepositoryImpl — one concrete instance bound to both interfaces
        single {
            ImageRepositoryImpl(
                imageDownloader = get(),
                imageStorage = get(),
                imageApi = get(),
                appScope =
                    get(
                        qualifier =
                            named(APP_SCOPE),
                    ),
            )
        }
        single<ImageRepository> { get<ImageRepositoryImpl>() }
        single<ImageStagingRepository> { get<ImageRepositoryImpl>() }

        // DownloadRepository — read-side of download state. Per-book commands still live
        // on DownloadService; W8 will consolidate.
        single<DownloadRepository> {
            DownloadRepositoryImpl(
                downloadDao = get(),
                bookRepository = get(),
                enqueuer = get(),
            )
        }

        // CoverDownloadRepository - owns scope for fire-and-forget cover downloads
        single<CoverDownloadRepository> {
            CoverDownloadRepositoryImpl(
                imageDownloader = get(),
                bookDao = get(),
                scope =
                    get(
                        qualifier =
                            named(APP_SCOPE),
                    ),
            )
        }

        // AvatarDownloadRepository - owns scope for fire-and-forget avatar downloads (mirrors CoverDownloadRepository)
        single<AvatarDownloadRepository> {
            AvatarDownloadRepositoryImpl(
                imageDownloader = get(),
                scope =
                    get(
                        qualifier =
                            named(APP_SCOPE),
                    ),
            )
        }

        // Cover Download Worker — processes the persistent cover download queue
        single {
            CoverDownloadWorker(
                coverDownloadDao = get(),
                imageDownloader = get(),
            )
        }
    }
