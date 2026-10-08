package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.EntityService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.EntityEditRepositoryImpl
import com.calypsan.listenup.client.domain.repository.EntityEditRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Story World entities: the [EntityService] channel (also the `entities` outbox sender's, in
 * `clientSyncModule`) and the offline-first [EntityEditRepository].
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.local.db.EntityDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.sync.OfflineEditor] — `clientSyncModule`
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory], the RPC auth recovery — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.domain.repository.AuthSession] — `authModule`
 */
internal val entityModule: Module =
    module {
        // EntityService RPC channel — edits' outbox sender, merge, history and revert. Authed (self-healing);
        // joins the RpcCacheInvalidator sweep.
        rpcChannel<EntityService>()

        single<EntityEditRepository> {
            EntityEditRepositoryImpl(
                entityDao = get(),
                offlineEditor = get(),
                channel = rpcChannel(),
                authSession = get(),
            )
        }
    }
