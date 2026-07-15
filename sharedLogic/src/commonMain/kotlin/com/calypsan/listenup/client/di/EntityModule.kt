package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.EntityService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.EntityEditRepositoryImpl
import com.calypsan.listenup.client.domain.repository.EntityEditRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Story World entity aggregate Koin wiring — the RPC channel declaration and the offline-first
 * edit repository (Story World Stage 2).
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.local.db.EntityDao] /
 *    [com.calypsan.listenup.client.data.local.db.BioEntryDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.sync.OfflineEditor] — `clientSyncModule`
 *
 * There is no online-only entity RPC surface (unlike [seriesModule]'s `mergeSeries`), so
 * [EntityEditRepositoryImpl] pulls no [com.calypsan.listenup.client.data.remote.RpcChannel]
 * directly — the [EntityService] channel declared here is resolved solely by the
 * `clientSyncModule` outbox sender.
 */
internal val entityModule: Module =
    module {
        // EntityService RPC channel — kotlinx.rpc dispatch for the entities outbox sender.
        rpcChannel<EntityService>()

        // EntityEditRepository — offline-first via OfflineEditor (Story World Stage 2).
        single<EntityEditRepository> {
            EntityEditRepositoryImpl(
                entityDao = get(),
                bioEntryDao = get(),
                offlineEditor = get(),
            )
        }
    }
