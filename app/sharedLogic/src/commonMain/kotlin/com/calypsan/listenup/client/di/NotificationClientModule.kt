package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.NotificationService
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.NotificationRepositoryImpl
import com.calypsan.listenup.client.domain.repository.NotificationRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Notification inbox Koin wiring — the RPC proxy and repository for the notifications domain.
 *
 * External dependencies (owned by other modules):
 *  - [com.calypsan.listenup.client.data.remote.ApiClientFactory] — `networkModule`
 *  - [com.calypsan.listenup.client.domain.repository.ServerConfig] — `settingsModule`
 *  - [com.calypsan.listenup.client.data.local.db.NotificationDao] — `persistenceModule`
 *  - [com.calypsan.listenup.client.data.sync.OfflineEditor] — `clientSyncModule`
 */
internal val notificationClientModule: Module =
    module {
        // NotificationService RPC channel — kotlinx.rpc dispatch for markRead's outbox sender
        // (clientSyncModule resolves it cross-module) and the preference surface. Inbox reads
        // come from Room via NotificationDao. Authed (self-healing) by default.
        rpcChannel<NotificationService>()

        // NotificationRepository — inbox observations from Room, markRead via the outbox,
        // preferences via RPC. NotificationDao provided by persistenceModule.
        single<NotificationRepository> {
            NotificationRepositoryImpl(
                channel = rpcChannel(),
                notificationDao = get(),
                offlineEditor = get(),
            )
        }
    }
