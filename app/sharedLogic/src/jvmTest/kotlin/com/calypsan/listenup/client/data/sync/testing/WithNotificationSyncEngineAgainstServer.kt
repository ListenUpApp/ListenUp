package com.calypsan.listenup.client.data.sync.testing

import com.calypsan.listenup.api.NotificationService
import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.NotificationMutation
import com.calypsan.listenup.api.dto.NotificationPreferenceDto
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.repository.NotificationRepositoryImpl
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.DomainDigestClient
import com.calypsan.listenup.client.data.sync.DomainPendingOperationSender
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.OutboxOpSender
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.data.sync.RpcSyncStreamClient
import com.calypsan.listenup.client.data.sync.SyncCatchUpClient
import com.calypsan.listenup.client.data.sync.SyncCursorStore
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.SyncEventDispatcher
import com.calypsan.listenup.client.data.sync.SyncReconciler
import com.calypsan.listenup.client.data.sync.domains.OutboxChannels
import com.calypsan.listenup.client.domain.repository.NotificationRepository as ClientNotificationRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase as ServerSqlDatabase
import com.calypsan.listenup.server.notifications.NotificationAudience
import com.calypsan.listenup.server.notifications.NotificationEmitter
import com.calypsan.listenup.server.notifications.NotificationPrefsRepository
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.push.NoOpPushNotifier
import com.calypsan.listenup.server.rpcguard.guard
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.NotificationRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.createSyncStreamService
import io.ktor.client.HttpClient
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

/** The single signed-in user every device in this harness authenticates as. */
internal const val NOTIFICATION_E2E_USER = "u1"

/**
 * One "device" in the notification E2E harness: a real [SyncEngine] over its own fresh in-memory
 * Room DB, plus the offline-first [ClientNotificationRepository] (Room reads, outbox markRead)
 * wired exactly as production DI wires it.
 */
internal data class NotificationSyncClient(
    val engine: SyncEngine,
    val clientDatabase: ListenUpDatabase,
    val notificationRepo: ClientNotificationRepository,
    val queue: PendingOperationQueue,
)

/**
 * Test scope for [NotificationSyncE2ETest][com.calypsan.listenup.client.data.sync.NotificationSyncE2ETest].
 *
 * Exposes the real server-side [NotificationEmitter] (to mint inbox rows the way business code
 * does), the server [NotificationRepository] and raw [serverSqlDb] (to assert server-side read
 * state), the primary device [clientA], and [newClient] to boot additional devices for the same
 * user — each with a fresh Room DB, so a late joiner converges purely via the catch-up pull.
 */
internal class NotificationSyncEngineScope(
    val emitter: NotificationEmitter,
    val serverNotificationRepo: NotificationRepository,
    val serverSqlDb: ServerSqlDatabase,
    val clientA: NotificationSyncClient,
    private val clientFactory: suspend () -> NotificationSyncClient,
) {
    /** Boots another device for the same user ([NOTIFICATION_E2E_USER]) with a fresh Room DB. */
    suspend fun newClient(): NotificationSyncClient = clientFactory()

    /**
     * Emits [event] to the single user [userId] through the real [NotificationEmitter]. Exposed
     * as a scope member so the test file never imports a `com.calypsan.listenup.server.*` symbol —
     * the ArchitectureTest server-import rule exempts this `testing/` fixture directory, not the
     * test files in `data/sync/` (the `TagSyncE2ETest` precedent: server objects reach tests only
     * through scope members).
     */
    suspend fun emitTo(
        userId: String,
        event: NotificationEvent,
    ) = emitter.emit(event, to = NotificationAudience.User(userId))

    /** The server row's `read_at` for [notificationId], or null (row absent or unread). */
    fun serverReadAt(notificationId: String): Long? =
        serverSqlDb.notificationsQueries
            .selectById(notificationId)
            .executeAsOneOrNull()
            ?.read_at

    /** Ids of the live server rows belonging to [userId], oldest first. */
    fun serverLiveIdsFor(userId: String): List<String> =
        serverSqlDb.notificationsQueries
            .selectLiveIdsForUserOldestFirst(userId, LIVE_IDS_PROBE_LIMIT)
            .executeAsList()

    private companion object {
        const val LIVE_IDS_PROBE_LIMIT = 100L
    }
}

/**
 * Boots a real `:server` test application AND one (or more) client engines in one process —
 * the [withTagSyncEngineAgainstServer] harness, adapted for the userScoped `notifications`
 * domain. Divergences from the tag sibling, each forced by the domain:
 *
 *  - **Server writes go through [NotificationEmitter]** (audience → prefs → row mint → prune),
 *    not a bare repository upsert — the emitter IS the production write path for this domain.
 *  - **The outbox drains through [HarnessNotificationService]**, a test implementation of the
 *    [NotificationService] contract delegating to the real server [NotificationRepository.markRead]
 *    ownership-checked path with a fixed caller. `:server`'s `NotificationServiceImpl` is
 *    `internal` with no public `create*`/`*ScopedTo` factories (unlike `createTagService`), so the
 *    real impl cannot be constructed cross-module; the service layer adds only principal
 *    resolution on top of the repository call the double makes verbatim.
 *  - **Multiple devices**: [NotificationSyncEngineScope.newClient] boots extra engines against
 *    the same server (fresh Room DB each), for cross-device convergence cases. All devices
 *    authenticate as [NOTIFICATION_E2E_USER] — the harness client sends no bearer, so
 *    [testAuth]'s default principal applies to every RPC connection.
 *  - **No book/library seeding** — notifications have no FK into books.
 */
internal fun withNotificationSyncEngineAgainstServer(block: suspend NotificationSyncEngineScope.() -> Unit) {
    testApplication {
        // ---- Server side: temp-file SQLite + the notifications domain ----
        val tmp = Files.createTempFile("listenup-notifications-e2e-", ".db").toFile().apply { deleteOnExit() }
        DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
        val serverDriver = DriverFactory().createDriver(tmp.absolutePath)
        val serverSqlDb = ServerSqlDatabase(serverDriver)
        val bus = ChangeBus()
        val syncRegistry = SyncRegistry()

        val serverNotificationRepo = NotificationRepository(serverSqlDb, bus, syncRegistry)
        val prefsRepo = NotificationPrefsRepository(serverSqlDb)
        val emitter =
            NotificationEmitter(
                db = serverSqlDb,
                repo = serverNotificationRepo,
                prefs = prefsRepo,
                notifier = NoOpPushNotifier(),
            )

        application {
            install(ServerContentNegotiation) { json(contractJson) }
            install(ServerKrpc)
            install(Authentication) { testAuth(defaultUserId = NOTIFICATION_E2E_USER) }
            install(Koin) {
                modules(
                    module {
                        single { bus }
                        single { syncRegistry }
                        single(createdAtStart = true) { serverNotificationRepo }
                    },
                )
            }
            routing {
                authenticate(JWT_PROVIDER) {
                    // The RPC sync surface — firehose AND catch-up pull with the per-connection
                    // principal scoping production uses. `notifications` is userScoped, so both
                    // paths route through the substrate's *ForUser variants for that principal.
                    serverRpc("/api/rpc/authed") {
                        rpcConfig { serialization { krpcJson(contractJson) } }
                        registerService<SyncStreamService> {
                            val p =
                                call.userPrincipalOrNull()
                                    ?: error("authed RPC mount reached without a principal")
                            guard(
                                createSyncStreamService(
                                    bus,
                                    syncRegistry,
                                    { error("no book-gated domain in this harness") },
                                    PrincipalProvider { p },
                                ),
                            )
                        }
                    }
                }
            }
        }

        // ---- Client side ----
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val testClient: HttpClient =
            createClient {
                install(ContentNegotiation) { json(contractJson) }
                installKrpc()
            }
        val bootedClients = mutableListOf<NotificationSyncClient>()

        // Boots one "device": fresh Room DB, real production sync-domain catalog, real engine,
        // and the production-shaped notifications outbox binding over the harness service.
        suspend fun bootClient(): NotificationSyncClient {
            val clientDb = createInMemoryTestDatabase()
            val registry = ClientSyncDomainRegistry()
            registerTestSyncDomains(db = clientDb, registry = registry)

            val state = SyncEngineState()
            val store = SyncCursorStore(clientDb.syncCursorDao())
            val notificationChannel =
                RpcChannel.forTest<NotificationService>(
                    HarnessNotificationService(serverNotificationRepo, prefsRepo, NOTIFICATION_E2E_USER),
                )
            val queue =
                PendingOperationQueue(
                    dao = clientDb.pendingOperationV2Dao(),
                    sender =
                        DomainPendingOperationSender(
                            mapOf(
                                OutboxChannels.Notifications.name to
                                    OutboxOpSender(OutboxChannels.Notifications) { _, mutation ->
                                        when (mutation) {
                                            is NotificationMutation.MarkRead -> {
                                                notificationChannel.call { it.markRead(mutation.notificationId) }
                                            }
                                        }
                                    },
                            ),
                        ),
                )
            val offlineEditor =
                OfflineEditor(
                    pendingQueue = queue,
                    transactionRunner = RoomTransactionRunner(clientDb),
                    authSession = FakeAuthSession(userId = NOTIFICATION_E2E_USER),
                )
            val notificationRepo: ClientNotificationRepository =
                NotificationRepositoryImpl(
                    channel = notificationChannel,
                    notificationDao = clientDb.notificationDao(),
                    offlineEditor = offlineEditor,
                )

            val syncStreamServiceProxy =
                testClient
                    .rpc("ws://localhost/api/rpc/authed") {
                        rpcConfig { serialization { krpcJson(contractJson) } }
                    }.withService<SyncStreamService>()
            val syncChannel = RpcChannel.forTest(syncStreamServiceProxy)

            val catchUp =
                SyncCatchUpClient(
                    channel = syncChannel,
                    store = store,
                    transactionRunner = RoomTransactionRunner(clientDb),
                )
            val syncStreamClient =
                RpcSyncStreamClient(
                    channel = syncChannel,
                    state = state,
                    scope = clientScope,
                )

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
            val digestClient = DomainDigestClient(channel = syncChannel)
            val reconciler = SyncReconciler(registry, store, digestClient, catchUp)
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

            return NotificationSyncClient(
                engine = engine,
                clientDatabase = clientDb,
                notificationRepo = notificationRepo,
                queue = queue,
            ).also { bootedClients += it }
        }

        try {
            val clientA = bootClient()
            try {
                NotificationSyncEngineScope(
                    emitter = emitter,
                    serverNotificationRepo = serverNotificationRepo,
                    serverSqlDb = serverSqlDb,
                    clientA = clientA,
                    clientFactory = { bootClient() },
                ).block()
            } finally {
                bootedClients.forEach { it.engine.stopAndJoin() }
            }
        } finally {
            clientScope.cancel()
            bootedClients.forEach { it.clientDatabase.close() }
            if (GlobalContext.getKoinApplicationOrNull() != null) {
                GlobalContext.stopKoin()
            }
        }
    }
}

/**
 * [NotificationService] over the real server [NotificationRepository]/[NotificationPrefsRepository]
 * with a fixed caller — what `NotificationServiceImpl` does after principal resolution. Exists
 * because that impl is `internal` to `:server` with no public factory (see the harness KDoc).
 */
private class HarnessNotificationService(
    private val repo: NotificationRepository,
    private val prefs: NotificationPrefsRepository,
    private val userId: String,
) : NotificationService {
    override suspend fun markRead(notificationId: String): AppResult<Unit> =
        repo.markRead(
            notificationId = notificationId,
            userId = userId,
            readAtMs = System.currentTimeMillis(),
        )

    override suspend fun getPreferences(): AppResult<List<NotificationPreferenceDto>> =
        AppResult.Success(prefs.listResolved(userId))

    override suspend fun updatePreference(
        type: String,
        preference: NotificationPreference,
    ): AppResult<Unit> =
        if (prefs.update(userId, type, preference)) {
            AppResult.Success(Unit)
        } else {
            AppResult.Failure(SyncError.NotFound(domain = "notification_prefs", entityId = type))
        }
}
