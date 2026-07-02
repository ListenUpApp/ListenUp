package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.remote.ContributorRpcFactory
import com.calypsan.listenup.client.data.remote.KtorContributorRpcFactory
import com.calypsan.listenup.client.data.repository.ContributorEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.ContributorRepositoryImpl
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.client.domain.usecase.contributor.ApplyContributorMetadataUseCase
import com.calypsan.listenup.client.domain.usecase.contributor.DeleteContributorUseCase
import com.calypsan.listenup.client.domain.usecase.contributor.UpdateContributorUseCase
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Contributor aggregate Koin wiring — RPC proxy, repositories, and use cases for the
 * contributor domain.
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.local.db.ContributorDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.BookDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.SearchDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.remote.ContributorApiContract] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.NetworkMonitor] — platform device module
 *  - [com.calypsan.listenup.client.domain.repository.ImageStorage] — platform storage module
 *  - `SyncDomainHandler<ContributorSyncPayload>` (named `contributors`) — `clientSyncRenovationModule`
 *  - [com.calypsan.listenup.client.domain.repository.MetadataRepository] — `bookModule`
 */
internal val contributorModule: Module =
    module {
        // ContributorRpcFactory - kotlinx.rpc proxy for ContributorService (cache-miss fetch).
        // Registered on the same bearer-gated /api/rpc/authed surface as BookService (Books-B2).
        single<ContributorRpcFactory> {
            KtorContributorRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // ContributorRepository for domain-layer contributor queries including search and metadata
        single<com.calypsan.listenup.client.domain.repository.ContributorRepository> {
            ContributorRepositoryImpl(
                contributorDao = get(),
                bookDao = get(),
                searchDao = get(),
                api = get(),
                networkMonitor = get(),
                imageStorage = get(),
                rpcFactory = get(),
                contributorSyncHandler =
                    get<SyncDomainHandler<ContributorSyncPayload>>(named(SyncDomains.CONTRIBUTORS.name)),
            )
        }

        // ContributorEditRepository — offline-first update via OfflineEditor;
        // merge/delete stay pure RPC dispatchers (Books-C1).
        single<ContributorEditRepository> {
            ContributorEditRepositoryImpl(
                contributorRpcFactory = get(),
                contributorDao = get(),
                offlineEditor = get(),
            )
        }

        // Contributor use cases
        factory {
            UpdateContributorUseCase(
                contributorEditRepository = get(),
            )
        }
        factory {
            DeleteContributorUseCase(
                contributorRepository = get(),
            )
        }
        factory {
            ApplyContributorMetadataUseCase(
                metadataRepository = get(),
            )
        }
    }
