package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.remote.KtorReadingOrderRpcFactory
import com.calypsan.listenup.client.data.remote.ReadingOrderRpcFactory
import com.calypsan.listenup.client.data.repository.ReadingOrderRepositoryImpl
import com.calypsan.listenup.client.domain.repository.ReadingOrderRepository
import com.calypsan.listenup.client.presentation.readingorder.ReadingOrderListViewModel
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Reading-order aggregate Koin wiring — RPC proxy, offline-first repository, and
 * the list ViewModel. The sync-domain handlers are NOT wired here: they are
 * catalog-declared ([com.calypsan.listenup.client.data.sync.domains.syncDomainCatalog])
 * and registered by the `ComposedHandlerRegistrar` in [clientSyncModule].
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.local.db.ReadingOrderDao] /
 *    [com.calypsan.listenup.client.data.local.db.ReadingOrderBookDao] /
 *    [com.calypsan.listenup.client.data.local.db.ReadingOrderFollowDao] /
 *    [com.calypsan.listenup.client.data.local.db.UserDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.sync.OfflineEditor] — `clientSyncModule`
 *  - [com.calypsan.listenup.client.domain.repository.AuthSession] — `clientAuthModule`
 */
internal val readingOrderModule: Module =
    module {
        // ReadingOrderRpcFactory — kotlinx.rpc proxy for ReadingOrderService
        // (Room reads; RPC create/delete/discovery + outbox replay target).
        single<ReadingOrderRpcFactory> {
            KtorReadingOrderRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
                authRecovery = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // ReadingOrderRepository — offline-first mutations via OfflineEditor
        // (SOLID: interface in domain, impl in data).
        single<ReadingOrderRepository> {
            ReadingOrderRepositoryImpl(
                dao = get(),
                bookDao = get(),
                followDao = get(),
                userDao = get(),
                rpcFactory = get(),
                offlineEditor = get(),
                authSession = get(),
            )
        }

        factory {
            ReadingOrderListViewModel(
                repository = get(),
            )
        }
    }
