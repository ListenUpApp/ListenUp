package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.SeriesEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.SeriesRepositoryImpl
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import com.calypsan.listenup.client.domain.usecase.series.UpdateSeriesUseCase
import org.koin.core.module.Module
import org.koin.core.qualifier.named
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
 *  - the [com.calypsan.listenup.api.SearchService] `RpcChannel` — `searchModule`
 *  - [com.calypsan.listenup.client.domain.repository.NetworkMonitor] — platform device module
 *  - [com.calypsan.listenup.client.domain.repository.ImageStorage] — platform storage module
 *  - [com.calypsan.listenup.client.data.sync.domains.seriesDomain] — `clientSyncModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageRepository] — `mediaModule`
 *  - [com.calypsan.listenup.client.domain.repository.ImageStagingRepository] — `mediaModule`
 */
internal val seriesModule: Module =
    module {
        // SeriesService RPC channel — kotlinx.rpc dispatch for SeriesService (cache-miss fetch and
        // series edits). Authed (self-healing) by default; joins the RpcCacheInvalidator sweep.
        rpcChannel<SeriesService>()

        // SeriesRepository for domain-layer series queries including search
        single<com.calypsan.listenup.client.domain.repository.SeriesRepository> {
            SeriesRepositoryImpl(
                seriesDao = get(),
                bookDao = get(),
                searchDao = get(),
                networkMonitor = get(),
                imageStorage = get(),
                channel = rpcChannel(),
                // SearchService channel owned by searchModule — powers server series autocomplete.
                searchChannel = rpcChannel(),
                seriesSyncHandler =
                    get<SyncDomainHandler<SeriesSyncPayload>>(named(SyncDomains.SERIES.name)),
            )
        }

        // SeriesEditRepository — offline-first via OfflineEditor (Books-C1, Offline-Edit-Sync Phase 2).
        single<SeriesEditRepository> {
            SeriesEditRepositoryImpl(
                channel = rpcChannel(),
                seriesDao = get(),
                offlineEditor = get(),
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
