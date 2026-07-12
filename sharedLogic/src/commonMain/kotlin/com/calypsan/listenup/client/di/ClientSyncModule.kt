package com.calypsan.listenup.client.di

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncDomainKey
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.rpcChannel
import com.calypsan.listenup.client.data.repository.PlaybackPrepareRepositoryImpl
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.client.data.connection.ConnectionCoordinator
import com.calypsan.listenup.client.data.connection.ConnectionHealthStore
import com.calypsan.listenup.client.data.connection.ReconnectionSupervisor
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
import com.calypsan.listenup.client.data.sync.domains.OutboxInFlightQuery
import com.calypsan.listenup.client.data.sync.orSuccessIfNotFound
import com.calypsan.listenup.client.data.sync.outboxBinding
import com.calypsan.listenup.client.data.sync.outboxSender
import com.calypsan.listenup.client.data.sync.domains.RefreshedDomainRouter
import com.calypsan.listenup.client.data.sync.domains.SyncDomainCatalog
import com.calypsan.listenup.client.data.sync.domains.syncDomainCatalog
import com.calypsan.listenup.client.data.repository.DefaultBookAvailability
import com.calypsan.listenup.client.data.repository.SseServerReachability
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPrepareRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.api.dto.BookMoodMutation
import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.dto.BookTagMutation
import com.calypsan.listenup.api.dto.CollectionBookMutation
import com.calypsan.listenup.api.dto.CollectionMutation
import com.calypsan.listenup.api.dto.ContributorMutation
import com.calypsan.listenup.api.dto.GenreMutation
import com.calypsan.listenup.api.dto.SeriesMutation
import com.calypsan.listenup.api.dto.ShelfBookMutation
import com.calypsan.listenup.api.dto.ShelfMutation
import com.calypsan.listenup.api.dto.TagMutation
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.MoodId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.core.TagId
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
        single {
            ConnectionHealthStore(
                engineState = get(),
                authStateFlow = get<AuthSession>().authState,
                errorBus = get(),
                clientIdentity = get(),
                localPreferences = get<LocalPreferences>(),
                scope = get(qualifier = named(APP_SCOPE)),
            )
        }
        single { PresenceRefreshSignal() }
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

        // PlaybackService RPC channel — kotlinx.rpc dispatch backing the position/listening-event
        // outbox senders below AND the prepare() seam (download-URL resolution, Cast handoff,
        // timeline build) via PlaybackPrepareRepository. One channel, every PlaybackService caller.
        rpcChannel<PlaybackService>()

        // The single public seam every PlaybackService.prepare caller shares. Wrapping the internal
        // channel lets cross-module callers (Cast in :sharedUI) reach prepare without touching it.
        single<PlaybackPrepareRepository> {
            PlaybackPrepareRepositoryImpl(channel = rpcChannel<PlaybackService>())
        }

        // The outbox sender map derives from OutboxChannels.all and is completeness-
        // checked at construction: a declared channel with no binding (or vice versa)
        // is an immediate require() failure, not a silent op drop.
        single<PendingOperationSender> {
            val bookChannel = rpcChannel<BookService>()
            val collectionChannel = rpcChannel<CollectionService>()
            val seriesChannel = rpcChannel<SeriesService>()
            val contributorChannel = rpcChannel<ContributorService>()
            val playbackChannel = rpcChannel<PlaybackService>()
            val profileChannel = rpcChannel<ProfileService>()
            val userPreferencesChannel = rpcChannel<UserPreferencesService>()
            val tagChannel = rpcChannel<TagService>()
            val moodChannel = rpcChannel<MoodService>()
            val shelfChannel = rpcChannel<ShelfService>()
            val genreChannel = rpcChannel<GenreService>()
            outboxSender(
                mapOf(
                    outboxBinding(OutboxChannels.Positions) { _, request ->
                        playbackChannel.call { it.recordPosition(request) }
                    },
                    outboxBinding(OutboxChannels.ListeningEvents) { _, request ->
                        playbackChannel.call { it.recordListeningEvent(request) }
                    },
                    outboxBinding(OutboxChannels.Books) { id, mutation ->
                        val bookId = BookId(id)
                        when (mutation) {
                            is BookMutation.Update -> {
                                bookChannel.call { it.updateBook(bookId, mutation.patch) }
                            }

                            is BookMutation.SetContributors -> {
                                bookChannel.call { it.setBookContributors(bookId, mutation.contributors) }
                            }

                            is BookMutation.SetSeries -> {
                                bookChannel.call { it.setBookSeries(bookId, mutation.series) }
                            }

                            is BookMutation.SetGenres -> {
                                bookChannel.call { it.setBookGenres(bookId, mutation.genres) }
                            }

                            is BookMutation.SetChapters -> {
                                bookChannel.call { it.setBookChapters(bookId, mutation.chapters) }
                            }

                            // Rides the books channel for FIFO + shield, but dispatches to CollectionService.
                            is BookMutation.SetCollections -> {
                                collectionChannel.call {
                                    it.setBookCollections(bookId, mutation.collectionIds.map(::CollectionId))
                                }
                            }

                            is BookMutation.DeleteCover -> {
                                bookChannel.call { it.deleteBookCover(bookId) }
                            }
                        }
                    },
                    // The op's entityId is the seriesId; the sender reconstructs the SeriesId from it.
                    outboxBinding(OutboxChannels.Series) { id, mutation ->
                        when (mutation) {
                            is SeriesMutation.Update -> {
                                seriesChannel.call { it.updateSeries(SeriesId(id), mutation.patch) }
                            }

                            is SeriesMutation.Delete -> {
                                seriesChannel.call { it.deleteSeries(SeriesId(id)) }.orSuccessIfNotFound()
                            }
                        }
                    },
                    // The op's entityId is the contributorId; the sender reconstructs the ContributorId from it.
                    outboxBinding(OutboxChannels.Contributors) { id, mutation ->
                        when (mutation) {
                            is ContributorMutation.Update -> {
                                contributorChannel.call { it.updateContributor(ContributorId(id), mutation.patch) }
                            }

                            is ContributorMutation.Delete -> {
                                contributorChannel
                                    .call {
                                        it.deleteContributor(
                                            ContributorId(id),
                                        )
                                    }.orSuccessIfNotFound()
                            }
                        }
                    },
                    outboxBinding(OutboxChannels.Preferences) { _, patch ->
                        userPreferencesChannel.call { it.updateMyPreferences(patch) }
                    },
                    outboxBinding(OutboxChannels.Profile) { _, patch ->
                        profileChannel.call { it.updateMyProfile(patch) }
                    },
                    // The op's entityId is the genreId; the sender reconstructs the GenreId from it.
                    outboxBinding(OutboxChannels.Genres) { id, mutation ->
                        when (mutation) {
                            is GenreMutation.Update -> {
                                genreChannel.call { it.updateGenre(GenreId(id), mutation.patch) }
                            }

                            is GenreMutation.Delete -> {
                                genreChannel.call { it.deleteGenre(GenreId(id)) }.orSuccessIfNotFound()
                            }
                        }
                    },
                    // The op's entityId is the tagId; the sender reconstructs the TagId from it.
                    outboxBinding(OutboxChannels.Tags) { id, mutation ->
                        when (mutation) {
                            is TagMutation.Rename -> tagChannel.call { it.renameTag(TagId(id), mutation.newName) }
                            is TagMutation.Delete -> tagChannel.call { it.deleteTag(TagId(id)) }.orSuccessIfNotFound()
                        }
                    },
                    // The op's entityId is the "$bookId:$tagId" envelope; the sender reads the ids from the payload.
                    outboxBinding(OutboxChannels.BookTags) { _, mutation ->
                        when (mutation) {
                            is BookTagMutation.Remove -> {
                                tagChannel.call { it.removeTagFromBook(BookId(mutation.bookId), TagId(mutation.tagId)) }
                            }
                        }
                    },
                    outboxBinding(OutboxChannels.BookMoods) { _, mutation ->
                        when (mutation) {
                            is BookMoodMutation.Remove -> {
                                moodChannel.call {
                                    it.removeMoodFromBook(
                                        BookId(mutation.bookId),
                                        MoodId(mutation.moodId),
                                    )
                                }
                            }
                        }
                    },
                    // The op's entityId is the shelfId; the sender reconstructs the ShelfId from it.
                    outboxBinding(OutboxChannels.Shelves) { id, mutation ->
                        when (mutation) {
                            is ShelfMutation.Update -> {
                                shelfChannel.call {
                                    it.updateShelf(ShelfId(id), mutation.name, mutation.description, mutation.isPrivate)
                                }
                            }

                            is ShelfMutation.Delete -> {
                                shelfChannel.call { it.deleteShelf(ShelfId(id)) }.orSuccessIfNotFound()
                            }
                        }
                    },
                    // The op's entityId is the "$shelfId:$bookId" envelope; the sender reads the ids from the payload.
                    outboxBinding(OutboxChannels.ShelfBooks) { _, mutation ->
                        when (mutation) {
                            is ShelfBookMutation.Add -> {
                                shelfChannel.call {
                                    it.addBookToShelf(ShelfId(mutation.shelfId), BookId(mutation.bookId))
                                }
                            }

                            is ShelfBookMutation.Remove -> {
                                shelfChannel.call {
                                    it.removeBookFromShelf(ShelfId(mutation.shelfId), BookId(mutation.bookId))
                                }
                            }
                        }
                    },
                    // The op's entityId is the collectionId; the sender reconstructs the CollectionId from it.
                    outboxBinding(OutboxChannels.Collections) { id, mutation ->
                        when (mutation) {
                            is CollectionMutation.Rename -> {
                                collectionChannel.call { it.renameCollection(CollectionId(id), mutation.newName) }
                            }

                            is CollectionMutation.Delete -> {
                                collectionChannel.call { it.deleteCollection(CollectionId(id)) }.orSuccessIfNotFound()
                            }
                        }
                    },
                    // The op's entityId is the "$collectionId:$bookId" envelope; the sender reads the ids from the payload.
                    outboxBinding(OutboxChannels.CollectionBooks) { _, mutation ->
                        when (mutation) {
                            is CollectionBookMutation.Add -> {
                                collectionChannel.call {
                                    it.addBookToCollection(CollectionId(mutation.collectionId), BookId(mutation.bookId))
                                }
                            }

                            is CollectionBookMutation.Remove -> {
                                collectionChannel.call {
                                    it.removeBookFromCollection(
                                        CollectionId(mutation.collectionId),
                                        BookId(mutation.bookId),
                                    )
                                }
                            }
                        }
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
            val reporter: ConnectionHealthStore = get()
            SyncSseClient(
                serverUrlProvider = { serverConfig.getActiveUrl()?.value },
                streamingClientProvider = { apiClientFactory.getStreamingClient() },
                state = get(),
                scope = get(qualifier = named(APP_SCOPE)),
                onAuthExhausted = {
                    reporter.report(AuthError.SessionExpired(debugInfo = "SSE auth exhausted after in-band refresh"))
                },
            )
        }

        single<CatchUp> {
            val apiClientFactory: ApiClientFactory = get()
            val serverConfig: ServerConfig = get()
            val reporter: ConnectionHealthStore = get()
            SyncCatchUpClient(
                httpClientProvider = { apiClientFactory.getClient() },
                serverUrlProvider = { serverConfig.getActiveUrl()?.value },
                store = get(),
                transactionRunner = get(),
                reportConnectionIssue = reporter::report,
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
                reportConnectionIssue = get<ConnectionHealthStore>()::report,
            )
        }

        single {
            SyncEventDispatcher(
                registry = get(),
                state = get(),
                cursorAdvance = { domain, rev -> get<SyncCursorStore>().setCursor(domain, rev) },
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
                // The refreshed tier's refresh strategies. Presence pings its hot signal (the social
                // repos collect it); server-info/preferences re-fetch through their repositories'
                // write-through side effects. These are the sole home of this wiring now — the
                // dispatcher routes refresh controls via RefreshedDomainRouter.
                pingPresence = { get<PresenceRefreshSignal>().ping() },
                refetchServerInfo = { val _ = get<InstanceRepository>().getServerInfo(forceRefresh = true) },
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
                sseClient = get(),
                reconciler = get(),
                dispatcher = get(),
                presenceRefreshSignal = get(),
                scope = get(qualifier = named(APP_SCOPE)),
                // Outbox liveness rides device reachability, not the SSE firehose — so local-first writes
                // push over RPC even while the firehose is down.
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
