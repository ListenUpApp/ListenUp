package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.ImageRepository
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Leaf verify for [seriesModule]. Per the architecture rubric every leaf Koin module is
 * covered by a `module.verify()` test in commonTest. The whitelist enumerates dependencies
 * the series bindings pull in but other modules own:
 *
 *  - [ApiClientFactory] — owned by `networkModule`.
 *  - [ServerConfig] — owned by `settingsModule`.
 *  - [SeriesDao] — owned by `persistenceModule`.
 *  - [BookDao] — owned by `persistenceModule`.
 *  - [SearchDao] — owned by `persistenceModule`.
 *  - the [RpcChannel] for `SearchService` — owned by `searchModule`.
 *  - [NetworkMonitor] — owned by the platform device module.
 *  - [ImageStorage] — owned by the platform storage module.
 *  - [SyncDomainHandler] (series) — owned by `clientSyncModule`.
 *  - [ImageRepository] — owned by `mediaModule`.
 *  - [ImageStagingRepository] — owned by `mediaModule`.
 */
@OptIn(KoinExperimentalAPI::class)
class SeriesModuleVerifyTest :
    FunSpec({

        test("seriesModule wires up against its declared external dependencies") {
            seriesModule.verify(
                extraTypes =
                    listOf(
                        SeriesDao::class,
                        BookDao::class,
                        SearchDao::class,
                        RpcChannel::class,
                        NetworkMonitor::class,
                        ImageStorage::class,
                        SyncDomainHandler::class,
                        ImageRepository::class,
                        ImageStagingRepository::class,
                        ApiClientFactory::class,
                        ServerConfig::class,
                    ),
            )
        }
    })
