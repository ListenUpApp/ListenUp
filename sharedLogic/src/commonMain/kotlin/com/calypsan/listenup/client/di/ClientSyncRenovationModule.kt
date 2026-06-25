package com.calypsan.listenup.client.di

import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.KtorPlaybackRpcFactory
import com.calypsan.listenup.client.data.remote.PlaybackRpcFactory
import com.calypsan.listenup.client.data.connection.ConnectionCoordinator
import com.calypsan.listenup.client.data.connection.ReconnectionSupervisor
import com.calypsan.listenup.client.data.sync.ACTIVITY_PRIME_LIMIT
import com.calypsan.listenup.client.data.sync.ActivityRefreshSignal
import com.calypsan.listenup.client.data.sync.CatchUp
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.DomainDigestClient
import com.calypsan.listenup.client.data.sync.DomainPendingOperationSender
import com.calypsan.listenup.client.data.sync.ListeningEventOpSender
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.PlaybackPositionOpSender
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.data.sync.SseClient
import com.calypsan.listenup.client.data.sync.SyncCatchUpClient
import com.calypsan.listenup.client.data.sync.SyncCursorStore
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncReconciler
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.SyncEventDispatcher
import com.calypsan.listenup.client.data.sync.SyncSseClient
import com.calypsan.listenup.client.data.sync.TagSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.BookMoodSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.BookTagSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.BookSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.MoodSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.CollectionBookSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.CollectionShareSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.CollectionSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.ShelfBookSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.ShelfSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.ContributorSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.GenreSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.LibraryFolderSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.LibrarySyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.ListeningEventSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.PlaybackPositionSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.SeriesSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.PublicProfileSyncDomainHandler
import com.calypsan.listenup.client.data.sync.handlers.UserStatsSyncDomainHandler
import com.calypsan.listenup.client.data.repository.DefaultBookAvailability
import com.calypsan.listenup.client.data.repository.SseServerReachability
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.usecase.activity.FetchActivitiesUseCase
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.ServerReachability
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module

private const val APP_SCOPE = "appScope"

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
internal val clientSyncRenovationModule =
    module {
        // DAOs needed by the renovated engine. Mirrors the legacy `repositoryModule`
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

        single<PendingOperationSender> {
            DomainPendingOperationSender(
                byDomain =
                    mapOf(
                        "playback_positions" to PlaybackPositionOpSender(rpcFactory = get()),
                        "listening_events" to ListeningEventOpSender(rpcFactory = get()),
                    ),
            )
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
                // The server's content-free presence nudge pings the shared signal so the social
                // repos (currently-listening, book-readers) re-fetch their ACL-filtered RPCs.
                onActiveSessionsChanged = { get<PresenceRefreshSignal>().ping() },
                // The server's content-free activity nudge pings the shared signal so the activity
                // feed re-fetches its ACL-filtered RPC page.
                onActivityChanged = { get<ActivityRefreshSignal>().ping() },
                // The server's content-free server-info nudge (admin changed name / remote URL):
                // re-fetch getServerInfo, whose persistRemoteUrl side-effect updates the stored
                // remote-URL fallback so an admin's new domain reaches us without a cold start.
                onServerInfoChanged = { get<InstanceRepository>().getServerInfo(forceRefresh = true) },
            )
        }

        single { BookEntityMapper() }

        single(createdAtStart = true) {
            TagSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            BookTagSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            MoodSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            BookMoodSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            BookSyncDomainHandler(
                database = get(),
                mapper = get(),
                transactionRunner = get(),
                imageStorage = get(),
                registry = get(),
                documentStorage = get(),
            )
        }
        single(createdAtStart = true) {
            ContributorSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                imageStorage = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            SeriesSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            GenreSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            PlaybackPositionSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            ListeningEventSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
                authSession = get(),
            )
        }
        single(createdAtStart = true) {
            UserStatsSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            LibrarySyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            LibraryFolderSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            CollectionSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            CollectionBookSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            CollectionShareSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            ShelfSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            ShelfBookSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }
        single(createdAtStart = true) {
            PublicProfileSyncDomainHandler(
                database = get(),
                transactionRunner = get(),
                registry = get(),
            )
        }

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
