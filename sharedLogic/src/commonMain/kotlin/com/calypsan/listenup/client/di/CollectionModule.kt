package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.client.data.remote.CollectionInboxApi
import com.calypsan.listenup.client.data.remote.CollectionInboxApiContract
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.CollectionRepositoryImpl
import com.calypsan.listenup.client.data.repository.InboxRepositoryImpl
import com.calypsan.listenup.client.domain.repository.CollectionRepository
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.usecase.collection.AddBooksToCollectionUseCase
import com.calypsan.listenup.client.domain.usecase.collection.CreateCollectionUseCase
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Collection aggregate Koin wiring — RPC proxy, repositories, and admin inbox for the
 * collection domain.
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.local.db.CollectionDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.CollectionBookDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.local.db.CollectionShareDao] — `persistenceModule`
 */
internal val collectionModule: Module =
    module {
        // CollectionService RPC channel — kotlinx.rpc dispatch for CollectionService (Room reads;
        // RPC mutations). Authed (self-healing) by default; joins the RpcCacheInvalidator sweep.
        rpcChannel<CollectionService>()

        // CollectionRepository — Room reads + CollectionService RPC writes (interface in domain, impl in data)
        single<CollectionRepository> {
            CollectionRepositoryImpl(
                collectionDao = get(),
                collectionBookDao = get(),
                collectionShareDao = get(),
                channel = rpcChannel(),
            )
        }

        // AdminInboxApi for the 1b admin collection-inbox REST routes
        single {
            CollectionInboxApi(clientFactory = get())
        } bind CollectionInboxApiContract::class

        // InboxRepository — admin collection-inbox over the 1b REST routes
        single<InboxRepository> {
            InboxRepositoryImpl(api = get())
        }

        // AddBooksToCollectionUseCase — bulk add for multi-select flows
        factory { AddBooksToCollectionUseCase(get()) }

        // CreateCollectionUseCase — create-and-add for the multi-select collection picker
        factory { CreateCollectionUseCase(get(), get()) }
    }
