package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.sync.CatchUp
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.SseClient
import com.calypsan.listenup.client.data.sync.SyncCatchUpClient
import com.calypsan.listenup.client.data.sync.SyncCursorStore
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.SyncEventDispatcher
import com.calypsan.listenup.client.data.sync.SyncSseClient
import com.calypsan.listenup.client.data.sync.TagSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.BookSyncDomainHandler
import com.calypsan.listenup.client.domain.repository.DownloadRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin module wiring the renovated client sync engine. Coexists with the legacy
 * `syncModule` until D2 cutover deletes the legacy entries.
 *
 * Lifecycle ordering: handlers are `createdAtStart = true` so they self-register
 * with the [ClientSyncDomainRegistry] before [SyncEngine] is started by app
 * bootstrap.
 *
 * [SyncEngine] owns frame collection so catch-up, cursor seeding, dispatch, and
 * SSE connect ordering stay in one lifecycle component.
 */
val clientSyncRenovationModule =
    module {
        // DAOs needed by the renovated engine. Mirrors the legacy `repositoryModule`
        // DAO-exposure pattern so Koin's `verify()` can see them as direct deps.
        single { get<ListenUpDatabase>().syncCursorDao() }
        single { get<ListenUpDatabase>().pendingOperationV2Dao() }

        single { ClientSyncDomainRegistry() }
        single { SyncEngineState() }
        single { SyncCursorStore(dao = get()) }

        // Sender: scaffolding only — no real domain has a write API yet. Books-C
        // onwards register concrete senders per (domain, opType).
        single<PendingOperationSender> {
            PendingOperationSender { AppResult.Success(Unit) }
        }
        single {
            PendingOperationQueue(
                dao = get(),
                sender = get(),
            )
        }

        single<SseClient> {
            val apiClientFactory: ApiClientFactory = get()
            val serverConfig: ServerConfig = get()
            SyncSseClient(
                serverUrlProvider = { serverConfig.getServerUrl()?.value },
                streamingClientProvider = { apiClientFactory.getStreamingClient() },
                state = get(),
                scope = get(qualifier = named("appScope")),
            )
        }

        single<CatchUp> {
            val apiClientFactory: ApiClientFactory = get()
            val serverConfig: ServerConfig = get()
            SyncCatchUpClient(
                httpClientProvider = { apiClientFactory.getClient() },
                serverUrlProvider = { serverConfig.getServerUrl()?.value },
                store = get(),
            )
        }

        single {
            SyncEventDispatcher(
                registry = get(),
                queue = get(),
                state = get(),
                cursorAdvance = { domain, rev -> get<SyncCursorStore>().setCursor(domain, rev) },
                // Route stale-cursor recovery through the engine so the full
                // disconnect → catchUp → reseed → reconnect lifecycle stays in
                // one place. Resolving SyncEngine lazily breaks the Koin
                // construction cycle (engine depends on dispatcher; dispatcher
                // only needs engine at runtime, not at construction).
                onCursorStale = { lastKnown -> get<SyncEngine>().handleCursorStale(lastKnown) },
            )
        }

        single { BookEntityMapper() }

        single(createdAtStart = true) { TagSyncDomainHandler(registry = get()) }
        single(createdAtStart = true) {
            BookSyncDomainHandler(
                database = get(),
                mapper = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }

        single {
            SyncEngine(
                registry = get(),
                queue = get(),
                state = get(),
                store = get(),
                catchUp = get(),
                sseClient = get(),
                dispatcher = get(),
                downloadRepository = get<DownloadRepository>(),
                scope = get(qualifier = named("appScope")),
            )
        }
    }
