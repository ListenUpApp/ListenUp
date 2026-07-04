package com.calypsan.listenup.client.data.sync.testing

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.DomainDigestClient
import com.calypsan.listenup.client.data.sync.DomainPendingOperationSender
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.SyncCatchUpClient
import com.calypsan.listenup.client.data.sync.SyncCursorStore
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.SyncEventDispatcher
import com.calypsan.listenup.client.data.sync.SyncReconciler
import com.calypsan.listenup.client.data.sync.SyncSseClient
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.collectionServiceScopedTo
import com.calypsan.listenup.server.api.createCollectionService
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase as ServerSqlDatabase
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.syncRoutes
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE as ServerSSE
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/**
 * The fixed admin id for [withCollectionSyncEngineAgainstServer]. The auth provider grants this
 * id [UserRole.ROOT]; every other bearer token authenticates as a [UserRole.MEMBER]. Owner-only
 * collection operations (create/share/revoke) are driven as this admin.
 */
internal const val COLLECTION_E2E_ADMIN_ID = "admin"

/**
 * The fixed member id for [withCollectionSyncEngineAgainstServer]. The member's client engine
 * connects as this user (the harness's client [HttpClient] sends `Authorization: Bearer member`),
 * so the firehose targets per-user `AccessChanged` frames and the access-filtered catch-up at it.
 */
internal const val COLLECTION_E2E_MEMBER_ID = "member"

/**
 * Test scope for
 * [CollectionSyncAndReconcileE2ETest][com.calypsan.listenup.client.data.sync.CollectionSyncAndReconcileE2ETest].
 *
 * Exposes the real server-side [CollectionService] scoped to the admin owner for driving
 * create / add-book / share / revoke writes, the server [BookRepository] for seeding books, and
 * the member's client [ListenUpDatabase] for asserting that events (and the `AccessChanged`
 * prune) land in the member's Room.
 */
internal data class CollectionSyncEngineScope(
    val engine: SyncEngine,
    val adminCollections: CollectionService,
    val serverBookRepository: BookRepository,
    val clientDatabase: ListenUpDatabase,
)

/**
 * Boots a real `:server` test application AND the MEMBER's client engine in one process, with a
 * real in-memory Room DB on the client side. The deliverable harness for the collection sync +
 * `AccessChanged` reconcile E2E.
 *
 * Wiring decisions specific to the access-gated collection surface (vs. the Tag/Book harnesses):
 *  - **Two roles, one process.** The auth provider grants [COLLECTION_E2E_ADMIN_ID] ROOT (drives
 *    owner-only writes through the real, access-gated [CollectionServiceImpl]) and everyone else
 *    MEMBER. The client [HttpClient] sends `Authorization: Bearer member` by default, so the
 *    member's catch-up + SSE firehose are gated to the member's accessible set — exactly the
 *    book-visibility boundary [BookAccessPolicy] enforces in production.
 *  - **The real firehose gate.** [syncRoutes] injects [BookAccessPolicy] from Koin, so the
 *    member's `/api/v1/sync/events` stream drops collection/book content events the member may
 *    not see and delivers the per-user `AccessChanged` control frame addressed to them.
 *  - **The reconcile is wired.** The dispatcher's `onAccessChanged` callback is bound to
 *    [SyncEngine.handleAccessChanged], so a server-emitted `AccessChanged` frame triggers the
 *    transient access-filtered catch-up + `pruneTo` — the load-bearing prune under test.
 *
 * Seeds the `test-library` / `test-folder` rows (FK targets for books) and the admin + member
 * `users` rows (the share path's `userExists` check requires the member to exist).
 *
 * Stops Koin in `finally` so subsequent tests start with a fresh container.
 */
internal fun withCollectionSyncEngineAgainstServer(block: suspend CollectionSyncEngineScope.() -> Unit) {
    testApplication {
        // ---- Server side: temp-file SQLite + collection/book domains + access policy ----
        val tmp = Files.createTempFile("listenup-collections-e2e-", ".db").toFile().apply { deleteOnExit() }
        // DatabaseFactory.init runs the migrations; the repos read/write through a SQLDelight driver
        // opened over that same already-migrated file.
        DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
        val serverDriver = DriverFactory().createDriver(tmp.absolutePath)
        val serverSqlDb = ServerSqlDatabase(serverDriver)
        val bus = ChangeBus()
        val syncRegistry = SyncRegistry()

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
        // The share path's userExists() check requires both principals to have rows.
        for ((id, role) in listOf(COLLECTION_E2E_ADMIN_ID to "ROOT", COLLECTION_E2E_MEMBER_ID to "MEMBER")) {
            serverDriver.execute(
                null,
                "INSERT INTO users(id, email, email_normalized, password_hash, role, display_name, status, " +
                    "created_at, updated_at) VALUES " +
                    "('$id', '$id@x', '$id@x', 'x', '$role', '$id', 'ACTIVE', $now, $now)",
                0,
            )
        }

        // Repos self-register into the SyncRegistry on construction, so syncRoutes() can look
        // them up by domain name. BookRepository needs contributor + series repos as deps.
        val contributorRepo = ContributorRepository(serverSqlDb, bus, syncRegistry)
        val seriesRepo = SeriesRepository(serverSqlDb, bus, syncRegistry)
        val genreRepo = GenreRepository(serverSqlDb, bus, syncRegistry)
        val bookRepo =
            BookRepository(
                db = serverSqlDb,
                bus = bus,
                registry = syncRegistry,
                driver = serverDriver,
                contributorRepository = contributorRepo,
                seriesRepository = seriesRepo,
                genreRepository = genreRepo,
            )
        val collectionRepo = CollectionRepository(serverSqlDb, bus, syncRegistry, driver = serverDriver)
        val collectionBookRepo = CollectionBookRepository(serverSqlDb, bus, syncRegistry, driver = serverDriver)
        val grantRepo = CollectionGrantRepository(serverSqlDb, bus, syncRegistry, driver = serverDriver)
        val bookAccessPolicy = BookAccessPolicy(serverSqlDb, serverDriver)

        val collectionService =
            createCollectionService(
                collectionRepo = collectionRepo,
                collectionBookRepo = collectionBookRepo,
                grantRepo = grantRepo,
                bus = bus,
                sql = serverSqlDb,
                bookRevisionTouch = bookRepo,
            )
        val adminCollections =
            collectionServiceScopedTo(
                collectionService,
                PrincipalProvider {
                    UserPrincipal(UserId(COLLECTION_E2E_ADMIN_ID), SessionId("s-admin"), UserRole.ROOT)
                },
            )

        application {
            install(ServerContentNegotiation) { json(contractJson) }
            install(ServerSSE)
            // Role-aware auth: the admin id authenticates as ROOT (sees + drives everything);
            // every other bearer token (i.e. the member) authenticates as MEMBER so the access
            // gate actually applies to their catch-up + firehose.
            install(Authentication) { collectionTestAuth(adminUserId = COLLECTION_E2E_ADMIN_ID) }
            install(Koin) {
                modules(
                    module {
                        single { bus }
                        single { syncRegistry }
                        // syncRoutes() injects BookAccessPolicy to gate the access-filtered
                        // catch-up + firehose for the four access-gated domains.
                        single { bookAccessPolicy }
                        single(createdAtStart = true) { bookRepo }
                        single(createdAtStart = true) { collectionRepo }
                        single(createdAtStart = true) { collectionBookRepo }
                        single(createdAtStart = true) { grantRepo }
                    },
                )
            }
            routing {
                authenticate(JWT_PROVIDER) { syncRoutes() }
            }
        }

        // ---- Client side: the MEMBER's engine ----
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clientDb = createInMemoryTestDatabase()
        val testClient: HttpClient =
            createClient {
                install(ContentNegotiation) { json(contractJson) }
                install(SSE)
                // The member's transports carry their bearer by default. SyncSseClient /
                // SyncCatchUpClient send no auth header themselves, so the default request
                // is the only place the member identity is attached — without it the firehose
                // would resolve as the auth fallback, not the member.
                defaultRequest { bearerAuth(COLLECTION_E2E_MEMBER_ID) }
            }

        try {
            val engine = buildMemberSyncEngine(clientDb, testClient, clientScope)
            try {
                CollectionSyncEngineScope(
                    engine = engine,
                    adminCollections = adminCollections,
                    serverBookRepository = bookRepo,
                    clientDatabase = clientDb,
                ).block()
            } finally {
                engine.stopAndJoin()
            }
        } finally {
            clientScope.cancel()
            clientDb.close()
            if (GlobalContext.getKoinApplicationOrNull() != null) {
                GlobalContext.stopKoin()
            }
        }
    }
}

/**
 * Assembles the MEMBER's client [SyncEngine]: registers the real production catalog's handlers
 * (including the four access-gated domains under test — books + the three collection domains)
 * into a fresh registry, wires the catch-up / SSE clients against [testClient], and — load-bearing
 * — binds the dispatcher's `onAccessChanged` to [SyncEngine.handleAccessChanged] so a firehose
 * `AccessChanged` frame triggers the transient catch-up + `pruneTo` reconcile under test.
 */
private fun buildMemberSyncEngine(
    clientDb: ListenUpDatabase,
    testClient: HttpClient,
    clientScope: CoroutineScope,
): SyncEngine {
    val registry = ClientSyncDomainRegistry()

    registerTestSyncDomains(db = clientDb, registry = registry)

    val state = SyncEngineState()
    val store = SyncCursorStore(clientDb.syncCursorDao())
    val queue =
        PendingOperationQueue(
            dao = clientDb.pendingOperationV2Dao(),
            sender = DomainPendingOperationSender(emptyMap()),
        )
    val catchUp =
        SyncCatchUpClient(
            httpClientProvider = { testClient },
            serverUrlProvider = { "" },
            store = store,
            transactionRunner = RoomTransactionRunner(clientDb),
        )
    val sseClient =
        SyncSseClient(
            serverUrlProvider = { "" },
            streamingClientProvider = { testClient },
            state = state,
            scope = clientScope,
        )

    var engineRef: SyncEngine? = null
    val dispatcher =
        SyncEventDispatcher(
            registry = registry,
            queue = queue,
            state = state,
            cursorAdvance = { domain, rev -> store.setCursor(domain, rev) },
            onCursorStale = {
                checkNotNull(engineRef) { "SyncEngine not yet constructed" }.handleCursorStale()
            },
            onAccessChanged = {
                checkNotNull(engineRef) { "SyncEngine not yet constructed" }.handleAccessChanged()
            },
        )

    val digestClient = DomainDigestClient(httpClientProvider = { testClient }, serverUrlProvider = { "" })
    val reconciler = SyncReconciler(registry, store, digestClient, catchUp)

    return SyncEngine(
        registry = registry,
        queue = queue,
        state = state,
        store = store,
        catchUp = catchUp,
        sseClient = sseClient,
        reconciler = reconciler,
        dispatcher = dispatcher,
        presenceRefreshSignal = PresenceRefreshSignal(),
        scope = clientScope,
    ).also { engineRef = it }
}

/**
 * Test-only [AuthenticationProvider] registered under [JWT_PROVIDER] for the collection E2E.
 *
 * Like [TestAuthProvider] it reads the `Authorization: Bearer <token>` blob as the user id, but
 * it assigns [UserRole.ROOT] to [adminUserId] and [UserRole.MEMBER] to everyone else — so the
 * member's catch-up + firehose are actually access-gated (ROOT bypasses the policy entirely).
 * A request with no bearer authenticates as the member, never as the admin.
 */
private class CollectionTestAuthProvider(
    config: Config,
) : AuthenticationProvider(config) {
    private val adminUserId: String = config.adminUserId

    class Config internal constructor(
        name: String,
        val adminUserId: String,
    ) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val bearerToken =
            (context.call.request.parseAuthorizationHeader() as? HttpAuthHeader.Single)
                ?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }
                ?.blob
        val userId = bearerToken ?: COLLECTION_E2E_MEMBER_ID
        val role = if (userId == adminUserId) UserRole.ROOT else UserRole.MEMBER
        context.principal(
            UserPrincipal(
                userId = UserId(userId),
                sessionId = SessionId("test-session-$userId"),
                role = role,
            ),
        )
    }
}

/** Installs a [CollectionTestAuthProvider] under [JWT_PROVIDER]. */
private fun io.ktor.server.auth.AuthenticationConfig.collectionTestAuth(adminUserId: String) {
    register(CollectionTestAuthProvider(CollectionTestAuthProvider.Config(JWT_PROVIDER, adminUserId)))
}
