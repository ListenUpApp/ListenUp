package com.calypsan.listenup.client.data.sync.testing

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.api.dto.ContributorMutation
import com.calypsan.listenup.api.dto.RecordListeningEventRequest
import com.calypsan.listenup.api.dto.RecordPositionRequest
import com.calypsan.listenup.api.dto.SeriesMutation
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.repository.BookEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.BookMutationLocalApply
import com.calypsan.listenup.client.data.repository.ContributorEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.ContributorRepositoryImpl
import com.calypsan.listenup.client.data.repository.GenreRepositoryImpl
import com.calypsan.listenup.client.data.repository.SeriesEditRepositoryImpl
import com.calypsan.listenup.client.data.repository.SeriesRepositoryImpl
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.DomainDigestClient
import com.calypsan.listenup.client.data.sync.DomainPendingOperationSender
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.OutboxOpSender
import com.calypsan.listenup.client.data.sync.orSuccessIfNotFound
import com.calypsan.listenup.client.data.sync.PendingOperation
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.data.sync.SyncCatchUpClient
import com.calypsan.listenup.client.data.sync.SyncCursorStore
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncReconciler
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.SyncEventDispatcher
import com.calypsan.listenup.client.data.sync.RpcSyncStreamClient
import com.calypsan.listenup.client.data.sync.SyncStreamClient
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.data.sync.domains.OutboxInFlightQuery
import com.calypsan.listenup.client.data.sync.domains.contributorsDomain
import com.calypsan.listenup.client.data.sync.domains.seriesDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.ContributorEditRepository
import com.calypsan.listenup.client.domain.repository.ContributorRepository as ClientContributorRepository
import com.calypsan.listenup.client.domain.repository.GenreRepository as ClientGenreRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.SeriesEditRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository as ClientSeriesRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.stubImageStorage
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.CollectionId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.bookServiceScopedTo
import com.calypsan.listenup.server.api.createBookService
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.api.contributorServiceScopedTo
import com.calypsan.listenup.server.api.createContributorService
import com.calypsan.listenup.server.api.createGenreService
import com.calypsan.listenup.server.api.createSearchService
import com.calypsan.listenup.server.api.createSeriesService
import com.calypsan.listenup.server.api.genreServiceScopedTo
import com.calypsan.listenup.server.api.seriesServiceScopedTo
import com.calypsan.listenup.server.cover.CoverStorage
import com.calypsan.listenup.server.sync.BookSearchReindexer
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase as ServerSqlDatabase
import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.server.rpcguard.guard
import com.calypsan.listenup.server.services.ActiveSessionRepository
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository as ServerGenreRepository
import com.calypsan.listenup.server.services.LibraryFolderRepository
import com.calypsan.listenup.server.services.LibraryRepository
import com.calypsan.listenup.server.services.ListeningEventRepository
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.services.UserStatsRepository
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.ActivitySyncRepository
import com.calypsan.listenup.server.services.BookReadsRepository
import com.calypsan.listenup.server.services.StatsRecorder
import com.calypsan.listenup.server.services.UserStatsBackfillService
import com.calypsan.listenup.server.services.UserStatsUpdater
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.server.sync.createSyncStreamService
import com.calypsan.listenup.server.sync.syncRoutes
import io.ktor.client.HttpClient
import kotlin.time.Clock
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.Krpc as ServerKrpc
import kotlinx.rpc.krpc.ktor.server.rpc as serverRpc
import kotlinx.rpc.krpc.serialization.json.json as krpcJson
import kotlinx.rpc.registerService
import kotlinx.rpc.withService
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/**
 * Test scope exposing engine internals for assertions in Tier 3 e2e tests.
 *
 * Covers two sync domains in one wiring: `tags` (via [recording] / [tagRepo],
 * the original Tags-only surface) and `books` (via [serverBookRepository] +
 * [clientDatabase], wired additively for the Books-A Tier 3 e2e suite). The
 * single client [ClientSyncDomainRegistry] routes firehose frames for both domains,
 * so a Books test asserts against [clientDatabase] while the legacy Tags tests
 * keep asserting against [recording] unchanged.
 *
 * @property engine the wired client engine; tests call `engine.start(userId)`
 *   themselves so the catch-up → SSE-connect order is observable
 * @property recording the test handler observing tag events for assertions
 * @property tagRepo the server-side tag repository for triggering writes
 *   server-side
 * @property serverBookRepository the server-side book repository for triggering
 *   `upsert` / `softDelete` writes that publish Books `SyncEvent`s
 * @property serverContributorRepository the server-side contributor repository; use
 *   [com.calypsan.listenup.server.services.ContributorRepository.resolveOrCreate]
 *   to seed a contributor before building a [BookSyncPayload] with its id
 * @property serverSeriesRepository the server-side series repository; use
 *   [com.calypsan.listenup.server.services.SeriesRepository.resolveOrCreate]
 *   to seed a series and publish a `series` [com.calypsan.listenup.server.sync.SyncEvent]
 * @property serverActiveSessionRepository the server-side active-session repository; use
 *   [com.calypsan.listenup.server.services.ActiveSessionRepository.upsert] to seed a session
 *   row that other users can observe, and [ActiveSessionRepository.deleteForUserBook] to
 *   simulate the completion cascade (which soft-deletes the row and publishes an SSE event).
 *   Use [ActiveSessionRepository.getForUser] to assert server-side state after the cascade.
 * @property serverPlaybackPositionRepository the server-side playback-position repository; use
 *   [com.calypsan.listenup.server.services.PlaybackPositionRepository.recordPosition] to write
 *   a position server-side and assert its SSE event lands in the client Room DB (server→client
 *   direction). Also use [PlaybackPositionRepository.getPosition] to assert that a
 *   client-enqueued pending op reached the server (client→server direction).
 * @property serverListeningEventRepository the server-side listening-event repository; use
 *   [com.calypsan.listenup.server.services.ListeningEventRepository.upsert] to write an event
 *   server-side and assert its SSE event + derived stats land in the client Room DB (server→client
 *   direction). Also use [ListeningEventRepository.pullSince] to assert that a client-enqueued
 *   pending op reached the server (client→server direction).
 * @property serverUserStatsRepository the server-side user-stats repository; use
 *   [com.calypsan.listenup.server.services.UserStatsRepository.getForUser] to assert that
 *   [UserStatsUpdater] populated the materialized stats row when a listening event was recorded.
 * @property serverLibraryRepository the server-side library repository; use [LibraryRepository.upsert]
 *   to create or update a library row and publish its SSE event, and [LibraryRepository.softDelete]
 *   to tombstone it. The client [com.calypsan.listenup.client.data.sync.domains.librariesDomain]
 *   handler applies these events into Room.
 * @property serverLibraryFolderRepository the server-side library-folder repository; use
 *   [LibraryFolderRepository.upsert] to add folders and [LibraryFolderRepository.softDelete] to
 *   remove them. Folder SSE events arrive via
 *   [com.calypsan.listenup.client.data.sync.domains.libraryFoldersDomain] into Room.
 * @property clientDatabase the client-side in-memory Room DB the real
 *   books sync handler applies Books events into; tests read it back
 * @property bookEditRepository client-side [BookEditRepository] backed by a real
 *   kotlinx.rpc [BookService] proxy connected to the harness's RPC route. Tests
 *   use this to exercise the client → RPC → server → SSE → Room round trip for
 *   book mutations (Books-C1+).
 * @property contributorEditRepository client-side [ContributorEditRepository] backed
 *   by a real kotlinx.rpc [ContributorService] proxy connected to the harness's RPC
 *   route. Tests use this to exercise the client → RPC → server → SSE → Room round
 *   trip for contributor mutations (the `deleteContributor` cascade in Books-C1+).
 * @property seriesEditRepository client-side [SeriesEditRepository] backed by a real
 *   kotlinx.rpc [SeriesService] proxy connected to the harness's RPC route. Tests use
 *   this to exercise the client → RPC → server → SSE → Room round trip for series
 *   mutations (the `mergeSeries` cascade in Books-C2+).
 * @property state observable engine state for ambient assertions
 * @property dispatcher the dispatcher routing SSE frames to handlers
 * @property queue the pending-operation queue for echo-match scenarios
 */
internal data class ClientEngineScope(
    val engine: SyncEngine,
    val recording: RecordingTagSyncDomainHandler,
    val tagRepo: TagRepository,
    val serverDriver: SqlDriver,
    val serverBookRepository: BookRepository,
    val serverContributorRepository: ContributorRepository,
    val serverSeriesRepository: SeriesRepository,
    val serverGenreRepository: ServerGenreRepository,
    val serverActiveSessionRepository: ActiveSessionRepository,
    val serverPlaybackPositionRepository: PlaybackPositionRepository,
    val serverListeningEventRepository: ListeningEventRepository,
    val serverUserStatsRepository: UserStatsRepository,
    val serverLibraryRepository: LibraryRepository,
    val serverLibraryFolderRepository: LibraryFolderRepository,
    val serverActivityRecorder: ActivityRecorder,
    val clientDatabase: ListenUpDatabase,
    val bookEditRepository: BookEditRepository,
    val contributorEditRepository: ContributorEditRepository,
    val seriesEditRepository: SeriesEditRepository,
    val contributorSearchRepository: ClientContributorRepository,
    val seriesSearchRepository: ClientSeriesRepository,
    val genreRepository: ClientGenreRepository,
    val state: SyncEngineState,
    val dispatcher: SyncEventDispatcher,
    val queue: PendingOperationQueue,
    val syncStreamClient: SyncStreamClient,
    val reconciler: SyncReconciler,
)

/**
 * Boots `:server`'s test application AND the client engine in one process,
 * with a real in-memory Room DB on the client side. Use for Tier 3 e2e tests.
 *
 * Wires:
 *  - server: temp-file SQLite + Koin module + `syncRoutes`, mirroring
 *    `server/.../testing/SyncTestApplication.kt`
 *  - client: Room in-memory DB + a real [SyncEngine] backed by [SyncCatchUpClient]
 *    and [RpcSyncStreamClient] talking to the server's testApplication over a real
 *    kotlinx.rpc WebSocket (relative URLs route in-process)
 *  - frame collection: the engine pipes `syncStreamClient.frames` through
 *    [SyncEventDispatcher.handle], exactly as production does.
 *
 * Stops Koin in `finally` so subsequent tests start with a fresh container.
 */
internal fun withClientSyncEngineAgainstServer(block: suspend ClientEngineScope.() -> Unit) {
    testApplication {
        // ---- Server side: in-memory SQLite + Tag and Book domains registered ----
        val tmp = Files.createTempFile("listenup-c3-", ".db").toFile().apply { deleteOnExit() }
        // DatabaseFactory.init runs the migrations on the temp file. The repos then read/write
        // through a SQLDelight driver opened over that same already-migrated file, so the driver
        // never calls Schema.create — it just opens a connection (harmless in WAL mode).
        DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
        val serverDriver = DriverFactory().createDriver(tmp.absolutePath)
        val serverSqlDb = ServerSqlDatabase(serverDriver)
        val bus = ChangeBus()
        val syncRegistry = SyncRegistry()
        val bookAccessPolicy = BookAccessPolicy(serverSqlDb, serverDriver)
        val serverRepos = buildServerRepositories(serverSqlDb, serverDriver, bus, syncRegistry)
        val bookService: BookService =
            createBookService(
                repo = serverRepos.bookRepo,
                contributorRepo = serverRepos.contributorRepo,
                seriesRepo = serverRepos.seriesRepo,
                coverStorage = CoverStorage(),
                sql = serverSqlDb,
                driver = serverDriver,
                genreRepo = serverRepos.genreRepo,
            )
        // The deleteContributor cascade test needs `ContributorService`. The reindexer
        // requires a [BookTagRepository] + [TagRepository]
        // pair; both are already constructed inside `buildServerRepositories` (tagRepo
        // for the Tags-domain tests), so only `BookTagRepository` is instantiated here.
        val bookSearchReindexer =
            BookSearchReindexer(
                bookTagRepository = BookTagRepository(serverSqlDb, bus, syncRegistry),
                tagRepository = serverRepos.tagRepo,
                db = serverSqlDb,
                driver = serverDriver,
            )
        val contributorService: ContributorService =
            createContributorService(
                contributorRepo = serverRepos.contributorRepo,
                bookRepo = serverRepos.bookRepo,
                reindexer = bookSearchReindexer,
                sqlDb = serverSqlDb,
                driver = serverDriver,
            )
        // The mergeSeries e2e test needs `SeriesService`.
        val seriesService: SeriesService =
            createSeriesService(
                seriesRepo = serverRepos.seriesRepo,
                bookRepo = serverRepos.bookRepo,
                reindexer = bookSearchReindexer,
                sqlDb = serverSqlDb,
                driver = serverDriver,
            )
        // Genres e2e tests need a `GenreService` against the same server scaffolding.
        val genreService: GenreService =
            createGenreService(
                genreRepository = serverRepos.genreRepo,
                bookRepository = serverRepos.bookRepo,
                reindexer = bookSearchReindexer,
                sqlDb = serverSqlDb,
                driver = serverDriver,
            )
        // The unified server-search e2e (contributor/series autocomplete via SearchService) needs a
        // `SearchService` over the same server scaffolding. Left unscoped — the ROOT test principal
        // bypasses the access policy.
        val searchService: SearchService = createSearchService(sqlDb = serverSqlDb, driver = serverDriver)

        application {
            install(ServerContentNegotiation) { json(contractJson) }
            // Install the kotlinx.rpc application plugin before any `rpc(...)` route
            // is declared — the server DSL errors otherwise ("RPC for server requires
            // WebSockets plugin to be installed firstly"). Matches production wiring
            // in `Application.module()`.
            install(ServerKrpc)
            // The e2e tests run as "u1". Setting defaultUserId = "u1" means the
            // firehose's bare WebSocket upgrade is resolved as user "u1" by
            // TestAuthProvider — so per-user events published for "u1" are
            // delivered to the client's firehose subscriber.
            install(Authentication) { testAuth(defaultUserId = "u1") }
            install(Koin) {
                modules(
                    module {
                        single { bus }
                        single { syncRegistry }
                        // The catch-up / digest routes inject BookAccessPolicy to access-filter the
                        // gated domains (books, activities, collections). Without it, any
                        // access-gated FORWARD catch-up in-harness fails to resolve. Same instance
                        // the RPC firehose registration below closes over.
                        single { bookAccessPolicy }
                        single(createdAtStart = true) { serverRepos.tagRepo }
                        single(createdAtStart = true) { serverRepos.bookRepo }
                        single(createdAtStart = true) { serverRepos.activeSessionRepo }
                        single(createdAtStart = true) { serverRepos.playbackPositionRepo }
                        single(createdAtStart = true) { serverRepos.listeningEventRepo }
                        single(createdAtStart = true) { serverRepos.userStatsRepo }
                    },
                )
            }
            routing {
                authenticate(JWT_PROVIDER) {
                    syncRoutes()
                    // BookService RPC route — Books-C1+ Tier 3 e2e harness mounts
                    // the bearer-gated `BookService` here so client→RPC→server
                    // round trips work in-process. `guard(...)` wraps the service
                    // with the KSP-generated InternalError-sanitizing decorator,
                    // matching production wiring in `RpcRoutes.rpcRoutes`.
                    serverRpc("/api/rpc/authed") {
                        rpcConfig { serialization { krpcJson(contractJson) } }
                        registerService<BookService> {
                            // getBook is access-gated by the caller principal; scope the
                            // service per-request exactly as production RpcRoutes does. The
                            // test principal authenticates as ROOT, which bypasses the policy.
                            val p =
                                call.userPrincipalOrNull()
                                    ?: error("authed RPC mount reached without a principal")
                            guard(bookServiceScopedTo(bookService, PrincipalProvider { p }))
                        }
                        // These services gate metadata mutations on the caller's canEdit
                        // flag; scope each per-request exactly as production RpcRoutes does.
                        // The test principal authenticates as ROOT, which passes implicitly.
                        registerService<ContributorService> {
                            val p =
                                call.userPrincipalOrNull()
                                    ?: error("authed RPC mount reached without a principal")
                            guard(contributorServiceScopedTo(contributorService, PrincipalProvider { p }))
                        }
                        registerService<SeriesService> {
                            val p =
                                call.userPrincipalOrNull()
                                    ?: error("authed RPC mount reached without a principal")
                            guard(seriesServiceScopedTo(seriesService, PrincipalProvider { p }))
                        }
                        registerService<GenreService> {
                            val p =
                                call.userPrincipalOrNull()
                                    ?: error("authed RPC mount reached without a principal")
                            guard(genreServiceScopedTo(genreService, PrincipalProvider { p }))
                        }
                        // SearchService is unscoped in the harness (ROOT bypasses the access policy),
                        // so it mounts without per-request principal scoping.
                        registerService<SearchService> { guard(searchService) }
                        // The RPC firehose — the same per-connection principal scoping production
                        // uses, over the harness's shared ChangeBus.
                        registerService<SyncStreamService> {
                            val p =
                                call.userPrincipalOrNull()
                                    ?: error("authed RPC mount reached without a principal")
                            guard(createSyncStreamService(bus, { bookAccessPolicy }, PrincipalProvider { p }))
                        }
                    }
                }
            }
        }

        // ---- Client-side test scope ----
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clientDb = createInMemoryTestDatabase()
        val testClient: HttpClient =
            createClient {
                install(ContentNegotiation) { json(contractJson) }
                // Tier 3 e2e: installKrpc() adds WebSockets + kotlinx.rpc plumbing
                // so the harness's RPC channels can open `.rpc("/api/rpc/authed")`
                // against the same in-process server. The relative-URL pattern
                // matches the REST catch-up client above.
                installKrpc()
            }
        // Real kotlinx.rpc BookService proxy over the in-process server, wrapped in a
        // no-reconnect test channel — backs BookEditRepositoryImpl and the books outbox
        // sender below (client → RPC → server → SSE → Room round trip).
        val bookServiceProxy =
            testClient
                .rpc("ws://localhost/api/rpc/authed") {
                    rpcConfig { serialization { krpcJson(contractJson) } }
                }.withService<BookService>()
        val bookChannel = RpcChannel.forTest(bookServiceProxy)
        // Real kotlinx.rpc ContributorService proxy over the in-process server, wrapped in a
        // no-reconnect test channel — backs ContributorEditRepositoryImpl and the contributor
        // outbox sender below (client → RPC → server → SSE → Room round trip).
        val contributorServiceProxy =
            testClient
                .rpc("ws://localhost/api/rpc/authed") {
                    rpcConfig { serialization { krpcJson(contractJson) } }
                }.withService<ContributorService>()
        val contributorChannel = RpcChannel.forTest(contributorServiceProxy)
        // Real kotlinx.rpc SearchService proxy over the in-process server, wrapped in a no-reconnect
        // test channel — backs the contributor/series search repos' never-stranded server search
        // (client repo → RPC → server SearchService → SearchResults).
        val searchServiceProxy =
            testClient
                .rpc("ws://localhost/api/rpc/authed") {
                    rpcConfig { serialization { krpcJson(contractJson) } }
                }.withService<SearchService>()
        val searchChannel = RpcChannel.forTest(searchServiceProxy)
        // Real kotlinx.rpc ProfileService / UserPreferencesService proxies over the in-process
        // server, wrapped in no-reconnect test channels — back the profile/preferences outbox
        // senders below. No harness test mounts these services server-side yet; the channels exist
        // so a future e2e test's queued op has a sender to resolve against, like every other domain.
        val profileServiceProxy =
            testClient
                .rpc("ws://localhost/api/rpc/authed") {
                    rpcConfig { serialization { krpcJson(contractJson) } }
                }.withService<ProfileService>()
        val profileChannel = RpcChannel.forTest(profileServiceProxy)
        val userPreferencesServiceProxy =
            testClient
                .rpc("ws://localhost/api/rpc/authed") {
                    rpcConfig { serialization { krpcJson(contractJson) } }
                }.withService<UserPreferencesService>()
        val userPreferencesChannel = RpcChannel.forTest(userPreferencesServiceProxy)
        // Real kotlinx.rpc GenreService proxy over the in-process server, wrapped in a
        // no-reconnect test channel via RpcChannel.forTest — a single cached proxy over a
        // real socket to the in-process server, matching the sibling Test*RpcFactory idiom.
        val genreServiceProxy =
            testClient
                .rpc("ws://localhost/api/rpc/authed") {
                    rpcConfig { serialization { krpcJson(contractJson) } }
                }.withService<GenreService>()
        // Real kotlinx.rpc CollectionService proxy over the in-process server, wrapped in a
        // no-reconnect test channel — backs BookEditRepositoryImpl.setBookCollections here.
        val collectionServiceProxy =
            testClient
                .rpc("ws://localhost/api/rpc/authed") {
                    rpcConfig { serialization { krpcJson(contractJson) } }
                }.withService<CollectionService>()
        val collectionChannel = RpcChannel.forTest(collectionServiceProxy)

        try {
            val registry = ClientSyncDomainRegistry()
            val recording = RecordingTagSyncDomainHandler(registry)
            val state = SyncEngineState()
            val store = SyncCursorStore(clientDb.syncCursorDao())
            // Wire real senders for `playback_positions` and `listening_events` so the
            // client→server direction is exercised end-to-end: the engine's reactive drain
            // dispatches ops through these senders to the server repositories in-process,
            // with no HTTP/RPC round-trip.
            val playbackSender = DirectPlaybackPositionSender(serverRepos.playbackPositionRepo)
            val listeningEventSender = DirectListeningEventSender(serverRepos.listeningEventRepo)
            // Real kotlinx.rpc SeriesService proxy over the in-process server, wrapped in a
            // no-reconnect test channel — backs SeriesEditRepositoryImpl and the series outbox
            // sender below (client → RPC → server → SSE → Room round trip).
            val seriesServiceProxy =
                testClient
                    .rpc("ws://localhost/api/rpc/authed") {
                        rpcConfig { serialization { krpcJson(contractJson) } }
                    }.withService<SeriesService>()
            val seriesChannel = RpcChannel.forTest(seriesServiceProxy)
            val queue =
                PendingOperationQueue(
                    dao = clientDb.pendingOperationV2Dao(),
                    sender =
                        DomainPendingOperationSender(
                            mapOf(
                                OutboxChannels.Positions.name to playbackSender,
                                OutboxChannels.ListeningEvents.name to listeningEventSender,
                                OutboxChannels.Books.name to
                                    OutboxOpSender(OutboxChannels.Books) { id, mutation ->
                                        val bookId = BookId(id)
                                        when (mutation) {
                                            is BookMutation.Update -> {
                                                bookChannel.call { it.updateBook(bookId, mutation.patch) }
                                            }

                                            is BookMutation.SetContributors -> {
                                                bookChannel.call {
                                                    it.setBookContributors(
                                                        bookId,
                                                        mutation.contributors,
                                                    )
                                                }
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

                                            is BookMutation.SetCollections -> {
                                                collectionChannel.call {
                                                    it.setBookCollections(
                                                        bookId,
                                                        mutation.collectionIds.map(::CollectionId),
                                                    )
                                                }
                                            }

                                            is BookMutation.DeleteCover -> {
                                                bookChannel.call { it.deleteBookCover(bookId) }
                                            }
                                        }
                                    },
                                OutboxChannels.Series.name to
                                    OutboxOpSender(OutboxChannels.Series) { id, mutation ->
                                        when (mutation) {
                                            is SeriesMutation.Update -> {
                                                seriesChannel.call { it.updateSeries(SeriesId(id), mutation.patch) }
                                            }

                                            is SeriesMutation.Delete -> {
                                                seriesChannel
                                                    .call { it.deleteSeries(SeriesId(id)) }
                                                    .orSuccessIfNotFound()
                                            }
                                        }
                                    },
                                OutboxChannels.Contributors.name to
                                    OutboxOpSender(OutboxChannels.Contributors) { id, mutation ->
                                        when (mutation) {
                                            is ContributorMutation.Update -> {
                                                contributorChannel.call {
                                                    it.updateContributor(ContributorId(id), mutation.patch)
                                                }
                                            }

                                            is ContributorMutation.Delete -> {
                                                contributorChannel
                                                    .call { it.deleteContributor(ContributorId(id)) }
                                                    .orSuccessIfNotFound()
                                            }
                                        }
                                    },
                                // No harness test yet drains a "preferences" op end-to-end (the
                                // RPC route isn't mounted server-side above); the entry exists so a
                                // future e2e test's queued op has a sender to resolve against,
                                // mirroring the Books/Series/Contributor registrations.
                                OutboxChannels.Preferences.name to
                                    OutboxOpSender(OutboxChannels.Preferences) { _, patch ->
                                        userPreferencesChannel.call { it.updateMyPreferences(patch) }
                                    },
                                // No harness test yet drains a "profile" op end-to-end (the RPC
                                // route isn't mounted server-side above, and no ProfileEditRepository
                                // is wired in this harness); the entry exists so a future e2e test's
                                // queued op has a sender to resolve against, mirroring the
                                // Preferences registration above.
                                OutboxChannels.Profile.name to
                                    OutboxOpSender(OutboxChannels.Profile) { _, patch ->
                                        profileChannel.call { it.updateMyProfile(patch) }
                                    },
                            ),
                        ),
                )

            // Real Books, Contributor, and Series handlers registered into the SAME registry — the
            // client dispatcher routes domain SSE frames here, applying them into the client Room DB
            // exactly as production does. Registered AFTER the queue so the anti-flicker shield can
            // consult the real outbox: an in-flight local edit shields its entity's inbound echoes.
            registerClientSyncHandlers(clientDb, registry, queue)

            val offlineEditor =
                OfflineEditor(
                    pendingQueue = queue,
                    transactionRunner = RoomTransactionRunner(clientDb),
                    authSession = FakeAuthSession(),
                )

            // Offline-first genre update/delete write to client Room and enqueue a "genres" op.
            val genreRepository: ClientGenreRepository =
                GenreRepositoryImpl(
                    dao = clientDb.genreDao(),
                    channel = RpcChannel.forTest(genreServiceProxy),
                    offlineEditor = offlineEditor,
                )

            // Offline-first book edits write to client Room and enqueue a "books" op;
            // the engine drains it through the OutboxOpSender above to the in-process
            // server, whose SSE echo reconciles client Room via the books sync handler.
            val bookEditRepository: BookEditRepository =
                BookEditRepositoryImpl(
                    offlineEditor = offlineEditor,
                    localApply =
                        BookMutationLocalApply(
                            bookDao = clientDb.bookDao(),
                            bookContributorDao = clientDb.bookContributorDao(),
                            contributorDao = clientDb.contributorDao(),
                            bookSeriesDao = clientDb.bookSeriesDao(),
                            seriesDao = clientDb.seriesDao(),
                            genreDao = clientDb.genreDao(),
                            chapterDao = clientDb.chapterDao(),
                            collectionBookDao = clientDb.collectionBookDao(),
                        ),
                    bookDao = clientDb.bookDao(),
                )

            // Offline-first series edits write to client Room and enqueue a "series" op;
            // the engine drains it through the OutboxOpSender above to the in-process
            // server, whose SSE echo reconciles client Room via the series composed handler.
            val seriesEditRepository: SeriesEditRepository =
                SeriesEditRepositoryImpl(
                    channel = seriesChannel,
                    seriesDao = clientDb.seriesDao(),
                    offlineEditor = offlineEditor,
                )

            // Offline-first contributor edits write to client Room and enqueue a
            // "contributors" op; the engine drains it through the OutboxOpSender above
            // to the in-process server, whose SSE echo reconciles client Room via
            // the contributors sync domain.
            val contributorEditRepository: ContributorEditRepository =
                ContributorEditRepositoryImpl(
                    channel = contributorChannel,
                    contributorDao = clientDb.contributorDao(),
                    offlineEditor = offlineEditor,
                )

            // Always-online NetworkMonitor so the search repos take the server (RPC) path rather
            // than the local-FTS fallback — the whole point of the unified-search e2e.
            val alwaysOnline = mock<NetworkMonitor> { every { isOnline() } returns true }

            // Read-side contributor/series repositories backed by the SAME clientDb + real RPC
            // channels. `searchContributors` / `searchSeries` route through `searchChannel` to the
            // in-process server's SearchService — the client→RPC→server search round trip. Their
            // cache-miss write-through handlers use a throwaway registry (search never touches them).
            val contributorSearchRepository: ClientContributorRepository =
                ContributorRepositoryImpl(
                    contributorDao = clientDb.contributorDao(),
                    bookDao = clientDb.bookDao(),
                    searchDao = clientDb.searchDao(),
                    networkMonitor = alwaysOnline,
                    imageStorage = stubImageStorage(),
                    channel = contributorChannel,
                    searchChannel = searchChannel,
                    contributorSyncHandler =
                        contributorsDomain(clientDb, stubImageStorage())
                            .toHandler(RoomTransactionRunner(clientDb), ClientSyncDomainRegistry()),
                )
            val seriesSearchRepository: ClientSeriesRepository =
                SeriesRepositoryImpl(
                    seriesDao = clientDb.seriesDao(),
                    bookDao = clientDb.bookDao(),
                    searchDao = clientDb.searchDao(),
                    networkMonitor = alwaysOnline,
                    imageStorage = stubImageStorage(),
                    channel = seriesChannel,
                    searchChannel = searchChannel,
                    seriesSyncHandler =
                        seriesDomain(clientDb).toHandler(RoomTransactionRunner(clientDb), ClientSyncDomainRegistry()),
                )

            val catchUp =
                SyncCatchUpClient(
                    httpClientProvider = { testClient },
                    // testApplication serves relative URLs in-process — an empty base URL is correct here.
                    serverUrlProvider = { "" },
                    store = store,
                    transactionRunner = RoomTransactionRunner(clientDb),
                )

            val digestClient = DomainDigestClient(httpClientProvider = { testClient }, serverUrlProvider = { "" })
            val reconciler = SyncReconciler(registry, store, digestClient, catchUp)

            // The production firehose client over a real kotlinx.rpc proxy into the harness's
            // in-process server — same transport shape as production DI wires.
            val syncStreamServiceProxy =
                testClient
                    .rpc("ws://localhost/api/rpc/authed") {
                        rpcConfig { serialization { krpcJson(contractJson) } }
                    }.withService<SyncStreamService>()
            val syncStreamClient =
                RpcSyncStreamClient(
                    channel = RpcChannel.forTest(syncStreamServiceProxy),
                    state = state,
                    scope = clientScope,
                )

            // Forward reference: dispatcher's onCursorStale callback needs to
            // call engine.handleCursorStale, but the engine takes the dispatcher
            // as a constructor dep. Production uses Koin's lazy `get<SyncEngine>()`
            // for this; here a single-slot holder serves the same role.
            var engineRef: SyncEngine? = null
            val dispatcher =
                SyncEventDispatcher(
                    registry = registry,
                    state = state,
                    cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
                    onCursorStale = {
                        checkNotNull(engineRef) { "SyncEngine not yet constructed" }
                            .handleCursorStale()
                    },
                )

            val engine =
                SyncEngine(
                    registry = registry,
                    queue = queue,
                    state = state,
                    store = store,
                    catchUp = catchUp,
                    syncStreamClient = syncStreamClient,
                    reconciler = reconciler,
                    dispatcher = dispatcher,
                    presenceRefreshSignal = PresenceRefreshSignal(),
                    scope = clientScope,
                )
            engineRef = engine

            try {
                ClientEngineScope(
                    engine = engine,
                    recording = recording,
                    tagRepo = serverRepos.tagRepo,
                    serverDriver = serverDriver,
                    serverBookRepository = serverRepos.bookRepo,
                    serverContributorRepository = serverRepos.contributorRepo,
                    serverSeriesRepository = serverRepos.seriesRepo,
                    serverGenreRepository = serverRepos.genreRepo,
                    serverActiveSessionRepository = serverRepos.activeSessionRepo,
                    serverPlaybackPositionRepository = serverRepos.playbackPositionRepo,
                    serverListeningEventRepository = serverRepos.listeningEventRepo,
                    serverUserStatsRepository = serverRepos.userStatsRepo,
                    serverLibraryRepository = serverRepos.libraryRepo,
                    serverLibraryFolderRepository = serverRepos.libraryFolderRepo,
                    serverActivityRecorder = serverRepos.activityRecorder,
                    clientDatabase = clientDb,
                    bookEditRepository = bookEditRepository,
                    contributorEditRepository = contributorEditRepository,
                    seriesEditRepository = seriesEditRepository,
                    contributorSearchRepository = contributorSearchRepository,
                    seriesSearchRepository = seriesSearchRepository,
                    genreRepository = genreRepository,
                    state = state,
                    dispatcher = dispatcher,
                    queue = queue,
                    syncStreamClient = syncStreamClient,
                    reconciler = reconciler,
                ).block()
            } finally {
                engine.stopAndJoin()
            }
        } finally {
            clientScope.cancel()
            clientDb.close()
            // Stop Koin so the next test starts fresh. Each test creates its own
            // `SyncRegistry` instance (passed into the per-test `TagRepository`),
            // so there's no process-wide state to clean up — `GlobalContext` is
            // only stopped because Ktor's Koin plugin starts it eagerly.
            if (GlobalContext.getKoinApplicationOrNull() != null) {
                GlobalContext.stopKoin()
            }
        }
    }
}

/** Server-side sync repositories wired for a single e2e test run. */
private data class ServerRepositories(
    val tagRepo: TagRepository,
    val contributorRepo: ContributorRepository,
    val seriesRepo: SeriesRepository,
    val genreRepo: ServerGenreRepository,
    val bookRepo: BookRepository,
    val activeSessionRepo: ActiveSessionRepository,
    val playbackPositionRepo: PlaybackPositionRepository,
    val listeningEventRepo: ListeningEventRepository,
    val userStatsRepo: UserStatsRepository,
    val libraryRepo: LibraryRepository,
    val libraryFolderRepo: LibraryFolderRepository,
    val activityRecorder: ActivityRecorder,
)

/**
 * Registers the real production catalog's handlers into [registry] — the client dispatcher
 * routes domain SSE frames here, applying them into [clientDb] exactly as production does.
 * The `tags` domain is excluded: this harness registers [RecordingTagSyncDomainHandler] for it
 * separately below (the registry throws on a second instance per domain name). [FakeAuthSession]
 * with the default "u1" user id matches the harness's `defaultUserId = "u1"` test auth provider,
 * so `listening_events` records against the same user the SSE firehose is scoped to.
 */
private fun registerClientSyncHandlers(
    clientDb: ListenUpDatabase,
    registry: ClientSyncDomainRegistry,
    queue: PendingOperationQueue,
) {
    registerTestSyncDomains(
        db = clientDb,
        registry = registry,
        authSession = FakeAuthSession(),
        exclude = setOf(SyncDomains.TAGS.name),
        // The anti-flicker shield consults the real outbox — the same wiring production does via
        // Koin — so the e2e proves an in-flight local edit is not clobbered by a stale echo.
        inFlightOutbox = OutboxInFlightQuery(queue::hasQueuedOpFor),
    )
}

/**
 * Builds all server-side sync repositories over an already-migrated [serverDriver].
 *
 * Wires [TagRepository], [ContributorRepository], [SeriesRepository], [BookRepository],
 * [PlaybackPositionRepository], [ListeningEventRepository], and [UserStatsRepository] in
 * dependency order without pulling in the full `booksModule` Koin graph (scanner / cover /
 * persister deps are not needed for the sync write → SSE → client surface exercised by
 * Tier 3 e2e tests).
 *
 * The books domain needs a configured library: [BookRepository]'s INSERT path resolves the
 * single library row through [LibraryRegistry], keyed off `LISTENUP_LIBRARY_PATH`. The path
 * only seeds a `libraries` DB row — it is never read from the filesystem on the sync write
 * path — so a throwaway temp dir is sufficient.
 *
 * The stats domain requires [UserStatsUpdater], which needs [UserStatsRepository] at
 * runtime (write path) and is needed by [ListeningEventRepository] at construction. The
 * `lateinit` trick from [SyncTestApplication] breaks the circular reference: the updater
 * lambda captures the `lateinit var` that is assigned immediately after construction.
 */
private fun buildServerRepositories(
    serverSqlDb: ServerSqlDatabase,
    serverDriver: SqlDriver,
    bus: ChangeBus,
    registry: SyncRegistry,
): ServerRepositories {
    // Seed the library + folder rows that BookSyncPayload fixtures reference via
    // libraryId = LibraryId("test-library") and folderId = FolderId("test-folder").
    // Required to satisfy the library_id FK on the books table (FK enforcement is on).
    // LibraryTable and LibraryFolderTable are `internal` — use raw SQL to insert.
    val now = System.currentTimeMillis()
    serverDriver.execute(
        null,
        "INSERT INTO libraries(id, name, created_at, updated_at, revision) " +
            "VALUES ('test-library', 'Test Library', $now, $now, 0)",
        0,
    )
    serverDriver.execute(
        null,
        "INSERT INTO library_folders(id, library_id, root_path, created_at, updated_at, revision) " +
            "VALUES ('test-folder', 'test-library', '/tmp/test-library', $now, $now, 0)",
        0,
    )
    val tagRepo = TagRepository(serverSqlDb, bus, registry)
    // Library and folder repos use their own bus+registry so their SSE events are published
    // on the shared bus and routed by the SyncRegistry to the catch-up / SSE subscriber.
    val libraryRepo = LibraryRepository(serverSqlDb, bus, registry)
    val libraryFolderRepo = LibraryFolderRepository(serverSqlDb, bus, registry, driver = serverDriver)
    val contributorRepo = ContributorRepository(serverSqlDb, bus, registry)
    val seriesRepo = SeriesRepository(serverSqlDb, bus, registry)
    val genreRepo = ServerGenreRepository(serverSqlDb, bus, registry)
    val bookRepo =
        BookRepository(
            db = serverSqlDb,
            bus = bus,
            registry = registry,
            driver = serverDriver,
            contributorRepository = contributorRepo,
            seriesRepository = seriesRepo,
            genreRepository = genreRepo,
        )
    val activeSessionRepo = ActiveSessionRepository(serverSqlDb, bus)
    val playbackPositionRepo =
        PlaybackPositionRepository(serverSqlDb, bus, registry, activeSessionRepo = activeSessionRepo)

    // Break the UserStatsRepository ↔ UserStatsUpdater cycle with a lateinit, matching
    // the pattern in SyncTestApplication.
    lateinit var statsUpdater: UserStatsUpdater
    val userStatsRepo =
        UserStatsRepository(
            db = serverSqlDb,
            bus = bus,
            registry = registry,
            userStatsUpdaterProvider = { statsUpdater },
        )
    val publicProfileMaintainer =
        PublicProfileMaintainer(serverSqlDb, PublicProfileRepository(serverSqlDb, bus, registry))
    statsUpdater = UserStatsUpdater(sql = serverSqlDb, userStatsRepo = userStatsRepo)
    // The activities syncable repo registers in the shared registry (+ driver) so the domain
    // participates in the harness's in-process sync (catch-up/digest/firehose). Built here (not
    // buried inside buildStatsRecorder) so the recorder can be exposed on the scope for e2e tests.
    val activityRecorder =
        ActivityRecorder(
            syncRepo = ActivitySyncRepository(db = serverSqlDb, bus = bus, registry = registry, driver = serverDriver),
        )
    val statsRecorder =
        buildStatsRecorder(serverSqlDb, userStatsRepo, publicProfileMaintainer, activityRecorder)
    val listeningEventRepo =
        ListeningEventRepository(
            db = serverSqlDb,
            bus = bus,
            registry = registry,
            statsRecorder = statsRecorder,
        )

    return ServerRepositories(
        tagRepo,
        contributorRepo,
        seriesRepo,
        genreRepo,
        bookRepo,
        activeSessionRepo,
        playbackPositionRepo,
        listeningEventRepo,
        userStatsRepo,
        libraryRepo,
        libraryFolderRepo,
        activityRecorder,
    )
}

private fun buildStatsRecorder(
    sqlDb: ServerSqlDatabase,
    statsRepo: UserStatsRepository,
    publicProfileMaintainer: PublicProfileMaintainer,
    activityRecorder: ActivityRecorder,
): StatsRecorder =
    StatsRecorder(
        sql = sqlDb,
        userStatsRepo = statsRepo,
        bookReadsRepository = BookReadsRepository(db = sqlDb),
        publicProfileMaintainer = publicProfileMaintainer,
        activityRecorder = activityRecorder,
        statsBackfill = UserStatsBackfillService(sql = sqlDb, userStatsRepo = statsRepo),
    )

/**
 * Test-only [PendingOperationSender] that routes a `playback_positions` pending op directly
 * to [PlaybackPositionRepository.recordPosition] without an HTTP/RPC round-trip.
 *
 * This is the correct test-double for the client→server direction in Tier 3 e2e tests:
 * the queue drain is real, the payload decoding is real, and the server write is real —
 * only the transport layer is short-circuited (in-process call instead of WebSocket RPC).
 * The resulting SSE event published by the repository is still real, so the round-trip
 * server→SSE→client Room assertion works without a network stack.
 */
internal class DirectPlaybackPositionSender(
    private val repository: PlaybackPositionRepository,
) : PendingOperationSender {
    override suspend fun send(op: PendingOperation): AppResult<Unit> {
        val request = contractJson.decodeFromString(RecordPositionRequest.serializer(), op.payload)
        val wireResult =
            repository.recordPosition(
                userId = op.ownerUserId,
                bookId = request.bookId,
                positionMs = request.positionMs,
                lastPlayedAt = request.lastPlayedAt,
                finished = request.finished,
                playbackSpeed = request.playbackSpeed,
                currentChapterId = request.currentChapterId,
            )
        return when (wireResult) {
            is com.calypsan.listenup.api.result.AppResult.Success -> AppResult.Success(Unit)
            is com.calypsan.listenup.api.result.AppResult.Failure -> AppResult.Failure(wireResult.error)
        }
    }
}

/**
 * Test-only [PendingOperationSender] that routes a `listening_events` pending op directly
 * to [ListeningEventRepository.upsert] without an HTTP/RPC round-trip.
 *
 * Mirrors [DirectPlaybackPositionSender]: queue drain and payload decoding are real, server
 * write is real, transport is short-circuited. The resulting SSE events (listening_events
 * upsert + user_stats update) are still published by the repository, so the full
 * server→SSE→client Room assertion works in-process.
 */
internal class DirectListeningEventSender(
    private val repository: ListeningEventRepository,
) : PendingOperationSender {
    override suspend fun send(op: PendingOperation): AppResult<Unit> {
        val request =
            contractJson.decodeFromString(RecordListeningEventRequest.serializer(), op.payload)
        val now = Clock.System.now().toEpochMilliseconds()
        val payload =
            ListeningEventSyncPayload(
                id = request.id,
                bookId = request.bookId,
                startPositionMs = request.startPositionMs,
                endPositionMs = request.endPositionMs,
                startedAt = request.startedAt,
                endedAt = request.endedAt,
                playbackSpeed = request.playbackSpeed,
                tz = request.tz,
                deviceLabel = request.deviceLabel,
                revision = 0L,
                updatedAt = now,
                createdAt = now,
                deletedAt = null,
            )
        val wireResult = repository.upsert(payload, clientOpId = null, userId = op.ownerUserId)
        return when (wireResult) {
            is com.calypsan.listenup.api.result.AppResult.Success -> AppResult.Success(Unit)
            is com.calypsan.listenup.api.result.AppResult.Failure -> AppResult.Failure(wireResult.error)
        }
    }
}
