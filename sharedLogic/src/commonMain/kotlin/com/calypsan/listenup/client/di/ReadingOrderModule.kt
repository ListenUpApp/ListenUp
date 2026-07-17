package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.ReadingOrderService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.ReadingOrderRepositoryImpl
import com.calypsan.listenup.client.domain.repository.ReadingOrderRepository
import com.calypsan.listenup.client.presentation.readingorder.ReadingOrderDetailViewModel
import com.calypsan.listenup.client.presentation.readingorder.ReadingOrderListViewModel
import com.calypsan.listenup.client.presentation.readingorder.SeriesReadingOrdersViewModel
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Reading-order aggregate Koin wiring — RPC proxy, offline-first repository, and the list,
 * per-series, and detail ViewModels. The sync-domain handlers are NOT wired here: they are
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
 *  - [com.calypsan.listenup.client.domain.repository.BookRepository] /
 *    [com.calypsan.listenup.client.domain.repository.SeriesRepository] — their own aggregate modules
 *  - [com.calypsan.listenup.core.error.ErrorBus] — the core error module
 */
internal val readingOrderModule: Module =
    module {
        // ReadingOrderService RPC channel — kotlinx.rpc dispatch for reading orders
        // (Room reads; RPC create/delete/discovery + outbox replay target).
        rpcChannel<ReadingOrderService>()

        // ReadingOrderRepository — offline-first mutations via OfflineEditor
        // (SOLID: interface in domain, impl in data).
        single<ReadingOrderRepository> {
            ReadingOrderRepositoryImpl(
                dao = get(),
                bookDao = get(),
                followDao = get(),
                userDao = get(),
                channel = rpcChannel(),
                offlineEditor = get(),
                authSession = get(),
            )
        }

        factory {
            ReadingOrderListViewModel(
                repository = get(),
            )
        }

        factory {
            SeriesReadingOrdersViewModel(
                readingOrderRepository = get(),
                errorBus = get(),
            )
        }

        factory {
            ReadingOrderDetailViewModel(
                readingOrderRepository = get(),
                bookRepository = get(),
                seriesRepository = get<com.calypsan.listenup.client.domain.repository.SeriesRepository>(),
                authSession = get(),
                errorBus = get(),
            )
        }
    }
