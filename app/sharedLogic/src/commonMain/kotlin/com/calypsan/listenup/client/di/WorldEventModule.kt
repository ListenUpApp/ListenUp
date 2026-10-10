package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.WorldEventService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.WorldEventEditRepositoryImpl
import com.calypsan.listenup.client.domain.repository.WorldEventEditRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Story World events: the [WorldEventService] channel (also the `world_events` outbox sender's, in
 * `clientSyncModule`) and the offline-first [WorldEventEditRepository].
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.local.db.WorldEventDao], [com.calypsan.listenup.client.data.local.db.EntityDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.sync.OfflineEditor] — `clientSyncModule`
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory], the RPC auth recovery — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.domain.repository.AuthSession] — `authModule`
 */
internal val worldEventModule: Module =
    module {
        // WorldEventService RPC channel — the batch sender, history and revert. Authed (self-healing); joins the
        // RpcCacheInvalidator sweep.
        rpcChannel<WorldEventService>()

        single<WorldEventEditRepository> {
            WorldEventEditRepositoryImpl(
                worldEventDao = get(),
                entityDao = get(),
                offlineEditor = get(),
                channel = rpcChannel(),
                authSession = get(),
            )
        }
    }
