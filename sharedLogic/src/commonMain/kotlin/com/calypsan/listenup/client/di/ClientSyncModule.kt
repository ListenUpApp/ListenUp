package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomainKey
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.data.remote.ContributorRpcFactory
import com.calypsan.listenup.client.data.remote.KtorPlaybackRpcFactory
import com.calypsan.listenup.client.data.remote.PlaybackRpcFactory
import com.calypsan.listenup.client.data.remote.ProfileRpcFactory
import com.calypsan.listenup.client.data.remote.SeriesRpcFactory
import com.calypsan.listenup.client.data.remote.UserPreferencesRpcFactory
import com.calypsan.listenup.client.data.connection.ConnectionCoordinator
import com.calypsan.listenup.client.data.connection.ReconnectionSupervisor
import com.calypsan.listenup.client.data.sync.ACTIVITY_PRIME_LIMIT
import com.calypsan.listenup.client.data.sync.ActivityRefreshSignal
import com.calypsan.listenup.client.data.sync.CatchUp
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.DomainDigestClient
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.data.sync.SseClient
import com.calypsan.listenup.client.data.sync.SyncCatchUpClient
import com.calypsan.listenup.client.data.sync.SyncCursorStore
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncReconciler
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.SyncEventDispatcher
import com.calypsan.listenup.client.data.sync.SyncSseClient
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.data.sync.domains.ComposedHandlerRegistrar
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.data.sync.outboxBinding
import com.calypsan.listenup.client.data.sync.outboxSender
import com.calypsan.listenup.client.data.sync.domains.RefreshedDomainRouter
import com.calypsan.listenup.client.data.sync.domains.SyncDomainCatalog
import com.calypsan.listenup.client.data.sync.domains.syncDomainCatalog
import com.calypsan.listenup.client.data.repository.DefaultBookAvailability
import com.calypsan.listenup.client.data.repository.SseServerReachability
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.usecase.activity.FetchActivitiesUseCase
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module

private const val APP_SCOPE = "appScope"

/**
 * Koin module wiring the client sync engine.
 *
 * Lifecycle ordering: handlers are `createdAtStart = true` so they self-register
 * with the [ClientSyncDomainRegistry] before [SyncEngine] is started by app
 * bootstrap.
 *
 * [SyncEngine] owns frame collection so catch-up, cursor seeding, dispatch, and
 * SSE connect ordering stay in one lifecycle component.
 */
internal val clientSyncModule =
    module {
        // DAOs needed by the sync engine. Mirrors the `repositoryModule`
        // DAO-exposure pattern so Koin's `verify()` can see them as direct deps.
        single { get<ListenUpDatabase>().syncCursorDao() }
        single { get<ListenUpDatabase>().pendingOperationV2Dao() }

        single { ClientSyncDomainRegistry() }
        single { SyncEngineState() }
        single { PresenceRefreshSignal() }
        single { ActivityRefreshSignal() }
        single<ServerReachability> {
            SseServerReachability(
                engineState = get(),
                scope = get(qualifier = named(APP_SCOPE)),
                // Lazy SyncEngine resolution mirrors onCursorStale/onAccessChanged: the
                // engine is only needed at retry time, not at construction, so this avoids
                // pulling SyncEngine into SseServerReachability's construction graph.
                reconnect = { get<SyncEngine>().reconnect() },
            )
        }
        single { SyncCursorStore(dao = get()) }

        single<PlaybackRpcFactory> {
            KtorPlaybackRpcFactory(
                apiClientFactory = get(),
                serverConfig = get(),
            )
        } binds arrayOf(com.calypsan.listenup.client.data.remote.RemoteCache::class)

        // The outbox sender map derives from OutboxChannels.all and is completeness-
        // checked at construction: a declared channel with no binding (or vice versa)
        // is an immediate require() failure, not a silent op drop.
        single<PendingOperationSender> {
            outboxSender(
                mapOf(
                    outboxBinding(OutboxChannels.Positions) { _, request ->
                        get<PlaybackRpcFactory>().playbackService().recordPosition(request)
                    },
                    outboxBinding(OutboxChannels.ListeningEvents) { _, request ->
                        get<PlaybackRpcFactory>().playbackService().recordListeningEvent(request)
                    },
                    outboxBinding(OutboxChannels.Books) { id, patch ->
                        get<BookRpcFactory>().bookService().updateBook(BookId(id), patch)
                    },
                    outboxBinding(OutboxChannels.Series) { id, patch ->
                        get<SeriesRpcFactory>().seriesService().updateSeries(SeriesId(id), patch)
                    },
                    outboxBinding(OutboxChannels.Contributors) { id, patch ->
                        get<ContributorRpcFactory>().contributorService().updateContributor(ContributorId(id), patch)
                    },
                    outboxBinding(OutboxChannels.Preferences) { _, patch ->
                        get<UserPreferencesRpcFactory>().get().updateMyPreferences(patch)
                    },
                    outboxBinding(OutboxChannels.Profile) { _, patch ->
                        get<ProfileRpcFactory>().get().updateMyProfile(patch)
                    },
                ),
            )
        }
        single {
            PendingOperationQueue(
                dao = get(),
                sender = get(),
            )
        }
        single { OfflineEditor(pendingQueue = get(), transactionRunner = get(), authSession = get()) }

        single<SseClient> {
            val apiClientFactory: ApiClientFactory = get()
            val serverConfig: ServerConfig = get()
            SyncSseClient(
                serverUrlProvider = { serverConfig.getActiveUrl()?.value },
                streamingClientProvider = { apiClientFactory.getStreamingClient() },
                state = get(),
                scope = get(qualifier = named(APP_SCOPE)),
            )
        }

        single<CatchUp> {
            val apiClientFactory: ApiClientFactory = get()
            val serverConfig: ServerConfig = get()
            SyncCatchUpClient(
                httpClientProvider = { apiClientFactory.getClient() },
                serverUrlProvider = { serverConfig.getActiveUrl()?.value },
                store = get(),
                transactionRunner = get(),
            )
        }

        single {
            val apiClientFactory: ApiClientFactory = get()
            val serverConfig: ServerConfig = get()
            DomainDigestClient(
                httpClientProvider = { apiClientFactory.getClient() },
                serverUrlProvider = { serverConfig.getActiveUrl()?.value ?: "" },
            )
        }

        single {
            SyncReconciler(
                registry = get(),
                store = get(),
                digestClient = get(),
                catchUp = get(),
            )
        }

        single {
            SyncEventDispatcher(
                registry = get(),
                queue = get(),
                state = get(),
                cursorAdvance = { domain, rev -> get<SyncCursorStore>().setCursor(domain, rev) },
                // The four content-free re-fetch nudges (presence, activity, server-info,
                // preferences) are catalog-declared RefreshedDomains, routed here.
                refreshedRouter = get(),
                // Route stale-cursor recovery through the engine so the full
                // disconnect → catchUp → reseed → reconnect lifecycle stays in
                // one place. Resolving SyncEngine lazily breaks the Koin
                // construction cycle (engine depends on dispatcher; dispatcher
                // only needs engine at runtime, not at construction).
                onCursorStale = { get<SyncEngine>().handleCursorStale() },
                // AccessChanged re-derives the accessible set via catch-up without tearing down
                // the live tail — same lazy-SyncEngine resolution as onCursorStale to break the
                // construction cycle.
                onAccessChanged = { get<SyncEngine>().handleAccessChanged() },
                // Account deleted by an admin: clear auth (soft logout → NeedsLogin),
                // the same path the 401 fallback uses. Resolved lazily to avoid pulling
                // AuthSession into the dispatcher's construction graph. The reason is logged
                // by the dispatcher; there's no existing one-shot channel here to surface it.
                onUserDeleted = { _ -> get<AuthSession>().clearAuthTokens() },
                // The server's content-free bulk-write nudge (e.g. an ABS import's firehose-suppressed
                // burst): the suppressed rows are written ABOVE the client cursor, so a digest-only
                // reconcile (forceReconcile) can't see them — it fingerprints AT the cursor. Route
                // through lifecycleReconcile(force = true), whose forward catch-up drains the
                // above-cursor rows before the digest pass. Same lazy-SyncEngine resolution as
                // onCursorStale/onAccessChanged to break the construction cycle.
                onLibraryDataChanged = { get<SyncEngine>().lifecycleReconcile(force = true) },
            )
        }

        single { BookEntityMapper() }

        single {
            syncDomainCatalog(
                database = get(),
                mapper = get(),
                imageStorage = get(),
                authSession = get(),
                avatarDownloadRepository = get(),
                // The nudge tier's refresh strategies. Presence/activity ping their hot
                // signals (social repos + activity feed collect them); server-info/preferences
                // re-fetch through their repositories' write-through side effects. These are the
                // sole home of this wiring now — the dispatcher routes nudges via RefreshedDomainRouter.
                pingPresence = { get<PresenceRefreshSignal>().ping() },
                pingActivity = { get<ActivityRefreshSignal>().ping() },
                refetchServerInfo = { val _ = get<InstanceRepository>().getServerInfo(forceRefresh = true) },
                refetchPreferences = { val _ = get<UserPreferencesRepository>().getPreferences() },
                documentStorage = get(),
            )
        }
        // Derived from the catalog's refreshed entries: one table maps each nudge control
        // to its refresh strategy, replacing the four ad-hoc nudge lambdas.
        single { RefreshedDomainRouter(get<SyncDomainCatalog>().refreshed) }
        single(createdAtStart = true) {
            ComposedHandlerRegistrar(
                catalog = get(),
                transactionRunner = get(),
                registry = get(),
            ).apply { registerAll() }
        }

        // Books' composed handler doubles as the on-demand aggregate write-through seam
        // (BookRepositoryImpl's cache-miss fallback fetch, PlaybackPreparer's ingest);
        // consumers inject it by qualified name.
        consumerSyncHandlerSingle(SyncDomains.BOOKS)

        // Series' composed handler doubles as the on-demand cache-miss write-through seam
        // (SeriesRepositoryImpl); consumers inject it by qualified name.
        consumerSyncHandlerSingle(SyncDomains.SERIES)

        // Contributors' composed handler doubles as the on-demand cache-miss write-through
        // seam (ContributorRepositoryImpl); consumers inject it by qualified name.
        consumerSyncHandlerSingle(SyncDomains.CONTRIBUTORS)

        single<BookAvailability> {
            DefaultBookAvailability(
                downloadRepository = get(),
                serverReachability = get(),
                networkMonitor = get(),
                localPreferences = get<LocalPreferences>(),
                playbackAvailable = get<Boolean>(qualifier = named("playbackAvailable")),
            )
        }

        single {
            val fetchActivities: FetchActivitiesUseCase = get()
            SyncEngine(
                registry = get(),
                queue = get(),
                state = get(),
                store = get(),
                catchUp = get(),
                sseClient = get(),
                reconciler = get(),
                dispatcher = get(),
                presenceRefreshSignal = get(),
                activityRefreshSignal = get(),
                scope = get(qualifier = named(APP_SCOPE)),
                // Prime / refresh the activity-feed Room cache on sync-start and reconnect, UI-independent.
                primeActivityFeed = { fetchActivities(ACTIVITY_PRIME_LIMIT) },
                // The nudge tier — the lifecycle-reconcile pass runs each entry's declared recovery so
                // a dropped nudge self-heals on the next foreground/reconnect edge (Plan §6a).
                refreshedDomains = get<SyncDomainCatalog>().refreshed,
            )
        }

        single(createdAtStart = true) {
            val coordinator: ConnectionCoordinator = get()
            ReconnectionSupervisor(
                engineState = get(),
                instanceRepository = get(),
                serverConfig = get(),
                sseClient = get(),
                authSession = get(),
                errorBus = get(),
                reevaluate = { coordinator.reevaluate() },
                scope = get(qualifier = named(APP_SCOPE)),
            ).apply { start() }
        }
    }

/**
 * Consumer-facing binding for a catalog-composed handler: resolves the handler the
 * [ComposedHandlerRegistrar] registered, under a [named] qualifier. Koin keys generic
 * singles on the ERASED [SyncDomainHandler] class, so every consumer-injected handler
 * single MUST carry its domain-name qualifier — two unqualified bindings collide at
 * graph construction. The cast is safe: [SyncDomainKey] ties the wire name to the
 * payload type in the contract.
 */
private inline fun <reified T : Any> Module.consumerSyncHandlerSingle(key: SyncDomainKey<T>) {
    single<SyncDomainHandler<T>>(named(key.name)) {
        // Force the registrar to run registerAll() before we look the handler up.
        val _ = get<ComposedHandlerRegistrar>()
        @Suppress("UNCHECKED_CAST")
        checkNotNull(
            get<ClientSyncDomainRegistry>().lookup(key.name) as SyncDomainHandler<T>?,
        ) { "${key.name} domain missing from the sync catalog" }
    }
}
