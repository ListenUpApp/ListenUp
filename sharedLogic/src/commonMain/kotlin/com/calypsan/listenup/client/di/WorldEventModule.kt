package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.WorldEventService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.WorldEventEditRepositoryImpl
import com.calypsan.listenup.client.domain.repository.WorldEventEditRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Story World event-log aggregate Koin wiring — the RPC channel declaration and the
 * offline-first edit repository.
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.local.db.WorldEventDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.sync.OfflineEditor] — `clientSyncModule`
 *
 * There is no online-only world-event RPC surface (unlike [seriesModule]'s `mergeSeries`), so
 * [WorldEventEditRepositoryImpl] pulls no [com.calypsan.listenup.client.data.remote.RpcChannel]
 * directly — the [WorldEventService] channel declared here is resolved solely by the
 * `clientSyncModule` outbox sender.
 */
internal val worldEventModule: Module =
    module {
        // WorldEventService RPC channel — kotlinx.rpc dispatch for the world-events outbox sender.
        rpcChannel<WorldEventService>()

        // WorldEventEditRepository — offline-first via OfflineEditor.
        single<WorldEventEditRepository> {
            WorldEventEditRepositoryImpl(
                worldEventDao = get(),
                offlineEditor = get(),
            )
        }
    }
