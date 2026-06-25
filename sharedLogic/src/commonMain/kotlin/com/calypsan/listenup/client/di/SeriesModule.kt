package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.KtorSeriesRpcFactory
import com.calypsan.listenup.client.data.remote.SeriesRpcFactory
import com.calypsan.listenup.client.data.repository.SeriesEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.SeriesRepositoryImpl
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import com.calypsan.listenup.client.domain.usecase.series.UpdateSeriesUseCase
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Series aggregate Koin wiring — RPC proxy, repositories, and use cases for the
 * series domain.
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.local.db.SeriesDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.BookDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.SearchDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.remote.SeriesApiContract] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.NetworkMonitor] — platform device module
 *  - [com.calypsan.listenup.client.domain.repository.ImageStorage] — platform storage module
 *  - [com.calypsan.listenup.client.data.sync.handlers.SeriesSyncDomainHandler] — `clientSyncRenovationModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageRepository] — `mediaModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageStagingRepository] — `mediaModule`
 */
internal val seriesModule: Module =
    module {
        // SeriesRpcFactory - kotlinx.rpc proxy for SeriesService (cache-miss fetch).
        // Registered on the same bearer-gated /api/rpc/authed surface as BookService (Books-B2).
        single<SeriesRpcFactory> {
            KtorSeriesRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // SeriesRepository for domain-layer series queries including search
        single<com.calypsan.listenup.client.domain.repository.SeriesRepository> {
            SeriesRepositoryImpl(
                seriesDao = get(),
                bookDao = get(),
                searchDao = get(),
                api = get(),
                networkMonitor = get(),
                imageStorage = get(),
                rpcFactory = get(),
                seriesSyncHandler = get<com.calypsan.listenup.client.data.sync.handlers.SeriesSyncDomainHandler>(),
            )
        }

        // SeriesEditRepository — pure RPC dispatcher (Books-C1).
        single<SeriesEditRepository> {
            SeriesEditRepositoryImpl(
                seriesRpcFactory = get(),
            )
        }

        // Series use cases
        factory {
            UpdateSeriesUseCase(
                seriesEditRepository = get(),
                imageRepository = get(),
                imageStagingRepository = get(),
            )
        }
    }
