package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.BookRatingService
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.ReadingOrderService
import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.sync.SyncDomainKey
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.connection.ConnectionCoordinator
import com.calypsan.listenup.client.data.connection.ConnectionHealthStore
import com.calypsan.listenup.client.data.connection.ReconnectionSupervisor
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.push.PushRegistrar
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.ConnectionHealthReachability
import com.calypsan.listenup.client.data.repository.DefaultBookAvailability
import com.calypsan.listenup.client.data.repository.PlaybackPrepareRepositoryImpl
import com.calypsan.listenup.client.data.sync.CatchUp
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.DomainDigestClient
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingImageUploadScheduler
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.data.sync.RpcSyncStreamClient
import com.calypsan.listenup.client.data.sync.SyncCatchUpClient
import com.calypsan.listenup.client.data.sync.SyncCursorStore
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.SyncEventDispatcher
import com.calypsan.listenup.client.data.sync.SyncFrameApplier
import com.calypsan.listenup.client.data.sync.SyncReconciler
import com.calypsan.listenup.client.data.sync.SyncStreamClient
import com.calypsan.listenup.client.data.sync.catalogOutboxBindings
import com.calypsan.listenup.client.data.sync.curationOutboxBindings
import com.calypsan.listenup.client.data.sync.domains.ComposedHandlerRegistrar
import com.calypsan.listenup.client.data.sync.domains.OutboxInFlightQuery
import com.calypsan.listenup.client.data.sync.domains.RefreshedDomainRouter
import com.calypsan.listenup.client.data.sync.domains.SyncDomainCatalog
import com.calypsan.listenup.client.data.sync.domains.syncDomainCatalog
import com.calypsan.listenup.client.data.sync.liveFrameCursorAdvance
import com.calypsan.listenup.client.data.sync.outboxSender
import com.calypsan.listenup.client.data.sync.playbackAndAccountOutboxBindings
import com.calypsan.listenup.client.data.sync.readingOrderOutboxBindings
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
import com.calypsan.listenup.client.domain.usecase.auth.LogoutUseCase
import com.calypsan.listenup.client.playback.platformCodecCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.core.qualifier.named
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
 * firehose connect ordering stay in one lifecycle component.
 */
internal val clientSyncModule =
    module {
        // DAOs needed by the sync engine. Mirrors the `repositoryModule`
        // DAO-exposure pattern so Koin's `verify()` can see them as direct deps.
        single { get<ListenUpDatabase>().syncCursorDao() }
        single { get<ListenUpDatabase>().pendingOperationV2Dao() }

        single { ClientSyncDomainRegistry() }
        // Read-your-writes: RpcChannel.callMutation routes a mutation's returned frames through this
        // into the same per-domain apply pipeline the firehose uses. Lives here (next to the registry
        // it depends on) so RpcChannel resolves it via getOrNull only in graphs that run the sync engine.
        single { SyncFrameApplier(registry = get()) }
        single { SyncEngineState() }
        single {
            ConnectionHealthStore(
                engineState = get(),
                authStateFlow = get<AuthSession>().authState,
                errorBus = get(),
                clientIdentity = get(),
                localPreferences = get<LocalPreferences>(),
                networkMonitor = get(),
                evidence = get(),
                scope = get(qualifier = named(APP_SCOPE)),
            )
        }
        single { PresenceRefreshSignal() }
        single<ServerReachability> {
            ConnectionHealthReachability(
                // Project the ONE connection-health source so the book-availability oracle can no
                // longer disagree with the shell banner about offline/healthy at the same instant.
                connectionHealth = get<ConnectionHealthStore>().state,
                firehoseConnected =
                    get<SyncEngineState>().observe().map { snapshot ->
                        snapshot.connection is ConnectionState.Connected
                    },
                scope = get(qualifier = named(APP_SCOPE)),
                // Route the banner Retry through the unified recover seam (re-resolve URL + re-open a
                // dead firehose + reconcile), not a bare re-dial of the same endpoint. Lazy
                // resolution mirrors onCursorStale/onAccessChanged — the repository is only needed at
                // retry time, not at construction, so this avoids a construction-graph cycle.
                reconnect = { get<SyncRepository>().recoverRealtime() },
            )
        }
        single { SyncCursorStore(dao = get()) }

        // PlaybackService RPC channel — kotlinx.rpc dispatch backing the position/listening-event
        // outbox senders below AND the prepare() seam (download-URL resolution, Cast handoff,
        // timeline build) via PlaybackPrepareRepository. One channel, every PlaybackService caller.
        rpcChannel<PlaybackService>()

        // The single public seam every PlaybackService.prepare caller shares. Wrapping the internal
        // channel lets cross-module callers (Cast in :app:sharedUI) reach prepare without touching it.
        single<PlaybackPrepareRepository> {
            PlaybackPrepareRepositoryImpl(
                channel = rpcChannel<PlaybackService>(),
                // Asked once at graph construction: the answer is a device property and does not
                // change within a session. Android queries MediaCodecList, the browser asks
                // canPlayType, the JVM answers "all".
                codecCapabilities = platformCodecCapabilities(),
            )
        }

        // A listener's rating — no dedicated feature module of its own yet, so the channel is
        // declared here beside PlaybackService's, next to the outbox binding that dispatches it.
        rpcChannel<BookRatingService>()

        // Reading orders (#962) — no feature module of their own until their repositories land, so the
        // channel is declared here beside the outbox bindings that dispatch it.
        rpcChannel<ReadingOrderService>()

        // The outbox sender map derives from OutboxChannels.all and is completeness-
        // checked at construction: a declared channel with no binding (or vice versa)
        // is an immediate require() failure, not a silent op drop.
        single<PendingOperationSender> {
            val collectionChannel = rpcChannel<CollectionService>()
            val bindings =
                playbackAndAccountOutboxBindings(
                    playbackChannel = rpcChannel(),
                    userPreferencesChannel = rpcChannel(),
                    profileChannel = rpcChannel(),
                    notificationChannel = rpcChannel(),
                ) +
                    catalogOutboxBindings(
                        bookChannel = rpcChannel(),
                        collectionChannel = collectionChannel,
                        seriesChannel = rpcChannel(),
                        contributorChannel = rpcChannel(),
                        genreChannel = rpcChannel(),
                        tagChannel = rpcChannel(),
                    ) +
                    curationOutboxBindings(
                        moodChannel = rpcChannel(),
                        bookRatingChannel = rpcChannel(),
                        shelfChannel = rpcChannel(),
                        collectionChannel = collectionChannel,
                        entityChannel = rpcChannel(),
                        worldEventChannel = rpcChannel(),
                    ) +
                    readingOrderOutboxBindings(rpcChannel())
            outboxSender(bindings.toMap())
        }
        single {
            PendingOperationQueue(
                dao = get(),
                sender = get(),
                transactionRunner = get(),
            )
        }
        single { OfflineEditor(pendingQueue = get(), transactionRunner = get(), authSession = get()) }

        // The firehose rides the RPC socket: one WebSocket, one connection-truth.
        rpcChannel<SyncStreamService>()
        single<SyncStreamClient> {
            RpcSyncStreamClient(
                channel = rpcChannel<SyncStreamService>(),
                state = get(),
                scope = get(qualifier = named(APP_SCOPE)),
            )
        }

        single<CatchUp> {
            val reporter: ConnectionHealthStore = get()
            SyncCatchUpClient(
                channel = rpcChannel<SyncStreamService>(),
                store = get(),
                transactionRunner = get(),
                reportConnectionIssue = reporter::report,
                // A failed catch-up on a digest-less domain (positions) must stop live frames from
                // stepping its cursor over the gap. Resolved lazily at call time, like the
                // dispatcher's engine callbacks, so construction order stays acyclic.
                onUnbackedDomainFailed = { domain -> get<SyncEventDispatcher>().holdCursorUntilCatchUp(domain) },
            )
        }

        single { DomainDigestClient(channel = rpcChannel<SyncStreamService>()) }

        single {
            SyncReconciler(
                registry = get(),
                store = get(),
                digestClient = get(),
                catchUp = get(),
                reportConnectionIssue = get<ConnectionHealthStore>()::report,
            )
        }

        single {
            SyncEventDispatcher(
                registry = get(),
                state = get(),
                // Only a pull creates a cursor: a live frame advances an existing one and never creates it.
                cursorAdvance = liveFrameCursorAdvance(get()),
                // Lets the dispatcher lift an opt-out (positions) freeze once catch-up has advanced
                // the persisted cursor past the frozen hole — otherwise one failed apply freezes live
                // cursor advancement for the whole session and forces a growing catch-up delta.
                cursorOf = { domain -> get<SyncCursorStore>().getCursor(domain) },
                // The three content-free re-fetch triggers (presence, server-info,
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
                onAccessChanged = { scope -> get<SyncEngine>().handleAccessChanged(scope) },
                // Account deleted by an admin: the full local sign-out, the same clean slate as Sign Out.
                // Launched on the app scope because this callback runs inside the engine's frame
                // collector, and the sign-out's first act is to stop and join that collector. Resolved
                // lazily to avoid pulling LogoutUseCase into the dispatcher's construction graph. The
                // reason is logged by the dispatcher; there's no existing one-shot channel here to
                // surface it.
                onUserDeleted = { _ ->
                    get<CoroutineScope>(qualifier = named(APP_SCOPE)).launch {
                        get<LogoutUseCase>().logoutLocally()
                    }
                },
                // The server's content-free bulk-write nudge (e.g. an ABS import's firehose-suppressed
                // burst): the suppressed rows are written ABOVE the client cursor, so a digest-only
                // reconcile (forceReconcile) can't see them — it fingerprints AT the cursor. Route
                // through lifecycleReconcile(force = true), whose forward catch-up drains the
                // above-cursor rows before the digest pass. Same lazy-SyncEngine resolution as
                // onCursorStale/onAccessChanged to break the construction cycle.
                // Positions have no digest, so their cursor may already sit past the imported rows:
                // ask the reconcile to re-read every digest-less domain from zero as well.
                onLibraryDataChanged = {
                    get<SyncReconciler>().requestUnbackedRepull()
                    get<SyncEngine>().lifecycleReconcile(force = true)
                },
                reportCompat = get<ConnectionHealthStore>()::reportCompat,
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
                // The refreshed tier's refresh strategies. Presence pings its hot signal (the social
                // repos collect it); server-info/preferences re-fetch through their repositories'
                // write-through side effects. These are the sole home of this wiring now — the
                // dispatcher routes refresh controls via RefreshedDomainRouter.
                pingPresence = { get<PresenceRefreshSignal>().ping() },
                refetchServerInfo = {
                    val _ = get<InstanceRepository>().getServerInfo(forceRefresh = true)
                    // The admin push toggle rides this same refetch: toggle flips → ServerInfoChanged →
                    // this forced refetch → PushRegistrar re-evaluates pushEnabled and (re)registers. We
                    // never unregister on disable — the server stops sending, and the token dies with the
                    // session (logout/session eviction is the existing cleanup path).
                    get<PushRegistrar>().syncRegistration()
                },
                refetchPreferences = { val _ = get<UserPreferencesRepository>().getPreferences() },
                documentStorage = get(),
            )
        }
        // Derived from the catalog's refreshed entries: one table maps each refresh control
        // to its refresh strategy, replacing the four ad-hoc refresh lambdas.
        single { RefreshedDomainRouter(get<SyncDomainCatalog>().refreshed) }
        single(createdAtStart = true) {
            ComposedHandlerRegistrar(
                catalog = get(),
                transactionRunner = get(),
                registry = get(),
                // The anti-flicker shield: every mirrored handler consults the outbox before applying
                // an inbound snapshot, so an in-flight local edit is never clobbered by a stale echo.
                inFlightOutbox = OutboxInFlightQuery(get<PendingOperationQueue>()::hasQueuedOpFor),
                // The access prune spares optimistic revision-0 rows while their add is still queued.
                hasQueuedOpIn = get<PendingOperationQueue>()::hasQueuedOpIn,
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
            SyncEngine(
                registry = get(),
                queue = get(),
                state = get(),
                store = get(),
                catchUp = get(),
                syncStreamClient = get(),
                reconciler = get(),
                dispatcher = get(),
                presenceRefreshSignal = get(),
                scope = get(qualifier = named(APP_SCOPE)),
                // Outbox liveness rides device reachability, not the sync firehose — so local-first
                // writes push over RPC even while the firehose is down.
                networkMonitor = get(),
                // The refreshed tier's router — the lifecycle-reconcile pass re-runs every refreshed
                // domain's refresh through it so a dropped refresh trigger self-heals on the next
                // foreground/reconnect edge (Plan §6a).
                refreshedRouter = get(),
                reportConnectionIssue = get<ConnectionHealthStore>()::report,
                // The §6.5 auth gate: park the firehose + outbox on SessionLapsed, resume on re-auth.
                authState = get<AuthSession>().authState,
            )
        }

        // Starts a pass over the pending image uploads when one can newly succeed: app start signed in,
        // the network returning, the server connection coming up. Lives beside the outbox's own drain
        // triggers but outside SyncEngine.
        single(createdAtStart = true) {
            PendingImageUploadScheduler(
                uploads = get(),
                authSession = get(),
                networkMonitor = get(),
                syncEngineState = get(),
                errorBus = get(),
                scope = get(qualifier = named(APP_SCOPE)),
            ).apply { start() }
        }

        single(createdAtStart = true) {
            val coordinator: ConnectionCoordinator = get()
            ReconnectionSupervisor(
                engineState = get(),
                instanceRepository = get(),
                serverConfig = get(),
                syncStreamClient = get(),
                authSession = get(),
                errorBus = get(),
                onServerReplaced = { url, instanceId ->
                    get<com.calypsan.listenup.client.domain.usecase.auth.AdoptServerUseCase>()(url, instanceId)
                },
                reevaluate = { coordinator.reevaluate() },
                scope = get(qualifier = named(APP_SCOPE)),
                reportProbe = get<ConnectionHealthStore>()::reportProbe,
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
