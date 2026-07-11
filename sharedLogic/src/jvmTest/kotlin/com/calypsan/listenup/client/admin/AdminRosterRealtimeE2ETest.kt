package com.calypsan.listenup.client.admin

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.AdminUserRosterSyncPayload
import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.remote.InviteRpcFactory
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.repository.AdminRepositoryImpl
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.DomainDigestClient
import com.calypsan.listenup.client.data.sync.DomainPendingOperationSender
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PresenceRefreshSignal
import com.calypsan.listenup.client.data.sync.SyncCatchUpClient
import com.calypsan.listenup.client.data.sync.SyncCursorStore
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.data.sync.SyncEventDispatcher
import com.calypsan.listenup.client.data.sync.SyncReconciler
import com.calypsan.listenup.client.data.sync.SyncSseClient
import com.calypsan.listenup.client.data.sync.testing.registerTestSyncDomains
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase as ServerSqlDatabase
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.services.GenreRepository as ServerGenreRepository
import com.calypsan.listenup.server.sync.AdminUserRosterRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.sync.syncRoutes
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE as ServerSSE
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

private const val ADMIN_ID = "admin-user"
private const val MEMBER_ID = "member-user"
private const val CLAIMED_USER_ID = "newly-claimed-user"
private const val SENTINEL_GENRE_ID = "sentinel-genre"
private const val ROUND_TRIP_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e for the `admin_user_roster` sync domain: a real server-side roster row publishes
 * through the real [syncRoutes] role gate, over a real client [SyncEngine] (catch-up + live SSE
 * firehose), into a real Room DB — proving
 * [com.calypsan.listenup.client.domain.repository.AdminRepository.observeRoster] actually
 * surfaces the row for an ADMIN session, and never for a MEMBER session.
 *
 * Two client [SyncEngine]s run in one process against the same in-process server, mirroring the
 * two-role wiring of
 * [com.calypsan.listenup.client.data.sync.testing.withCollectionSyncEngineAgainstServer] (a
 * role-aware [AuthenticationProvider] grants [ADMIN_ID] `ADMIN` and every other bearer token
 * `MEMBER`), but self-contained here since only the `admin_user_roster` + `genres` domains are
 * needed — no RPC/WebSocket surface, no books/collections access-gating machinery.
 *
 * The roster row is seeded directly through [AdminUserRosterRepository.upsert], mirroring
 * [com.calypsan.listenup.server.sync.AdminUserRosterGateTest]: the maintainer's publish triggers
 * (register / claim / approve / deny / delete) are already proven server-side by
 * `AuthServiceRosterPublishTest` / `InviteServiceRosterPublishTest` /
 * `AdminUserServiceRosterPublishTest` / `AdminUserRosterMaintainerTest`. This test's unique
 * surface is the client chain those tests can't reach: the role gate in [syncRoutes], the real
 * [SyncEngine] catch-up + firehose, the `admin_user_roster` descriptor applying into Room, and
 * [AdminRepositoryImpl.observeRoster] reading it back.
 *
 * The negative assertion borrows the witness technique from
 * [com.calypsan.listenup.server.sync.LibraryFolderSyncAccessTest]: an ungated `genres` sentinel
 * event is published right after the roster row, and the test waits for the SENTINEL to land in
 * the member's Room. The server firehose evaluates bus events strictly in publish order per
 * subscriber and drops the roster event *before ever sending it* to a MEMBER connection — so the
 * member's Room observing the sentinel proves the roster event was already evaluated (and
 * withheld) by that point. No arbitrary sleep is needed to prove the negative.
 */
class AdminRosterRealtimeE2ETest :
    FunSpec({

        test("a claimed user appears on the admin's roster in real time, never on a member's") {
            testApplication {
                val tmp =
                    Files.createTempFile("listenup-admin-roster-e2e-", ".db").toFile().apply { deleteOnExit() }
                // DatabaseFactory.init runs the migrations on the temp file. The repos then read/write
                // through a SQLDelight driver opened over that same already-migrated file.
                DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
                val serverDriver = DriverFactory().createDriver(tmp.absolutePath)
                val serverSqlDb = ServerSqlDatabase(serverDriver)
                val bus = ChangeBus()
                val syncRegistry = SyncRegistry()
                // Repos self-register into syncRegistry on construction, so syncRoutes() can look
                // them up by domain name.
                val rosterRepo = AdminUserRosterRepository(serverSqlDb, bus, syncRegistry, serverDriver)
                val genreRepo = ServerGenreRepository(serverSqlDb, bus, syncRegistry)

                application {
                    install(ServerContentNegotiation) { json(contractJson) }
                    install(ServerSSE)
                    // Role-aware auth: ADMIN_ID authenticates as ADMIN (passes the admin_user_roster
                    // whole-domain gate); every other bearer token authenticates as MEMBER so the
                    // gate actually applies to their catch-up + firehose.
                    install(Authentication) { adminRosterTestAuth(adminUserId = ADMIN_ID) }
                    install(Koin) {
                        modules(
                            module {
                                single { bus }
                                single { syncRegistry }
                            },
                        )
                    }
                    routing {
                        authenticate(JWT_PROVIDER) { syncRoutes() }
                    }
                }

                val adminScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val memberScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val adminDb = createInMemoryTestDatabase()
                val memberDb = createInMemoryTestDatabase()

                val adminHttpClient: HttpClient =
                    createClient {
                        install(ContentNegotiation) { json(contractJson) }
                        install(SSE)
                        defaultRequest { bearerAuth(ADMIN_ID) }
                    }
                val memberHttpClient: HttpClient =
                    createClient {
                        install(ContentNegotiation) { json(contractJson) }
                        install(SSE)
                        defaultRequest { bearerAuth(MEMBER_ID) }
                    }

                val adminEngine = buildRosterSyncEngine(adminDb, adminHttpClient, adminScope)
                val memberEngine = buildRosterSyncEngine(memberDb, memberHttpClient, memberScope)

                try {
                    adminEngine.start(currentUserId = ADMIN_ID)
                    memberEngine.start(currentUserId = MEMBER_ID)

                    // A user is admitted to the roster. Seeded directly (see class KDoc) — the
                    // maintainer's own trigger paths are covered elsewhere.
                    rosterRepo.upsert(rosterRowFixture(CLAIMED_USER_ID))
                    // Sentinel on an ungated domain, published right after the roster row — the
                    // witness for the member's negative assertion below.
                    genreRepo.upsert(
                        GenreSyncPayload(
                            id = SENTINEL_GENRE_ID,
                            name = "Sentinel",
                            slug = "sentinel",
                            path = "/sentinel",
                            parentId = null,
                            depth = 0,
                            sortOrder = 0,
                        ),
                    )

                    // ── Admin sees the roster, live, via the real consumption path ──
                    awaitUntil { adminDb.adminUserRosterDao().findById(CLAIMED_USER_ID) != null }
                    val adminRoster = adminRepositoryOver(adminDb).observeRoster().first()
                    adminRoster.map { it.id } shouldBe listOf(CLAIMED_USER_ID)
                    adminRoster.single().email shouldBe "$CLAIMED_USER_ID@example.com"

                    // ── Member never does — proven via the sentinel witness (see class KDoc) ──
                    awaitUntil { memberDb.genreDao().getById(SENTINEL_GENRE_ID) != null }
                    memberDb.adminUserRosterDao().findById(CLAIMED_USER_ID) shouldBe null
                    adminRepositoryOver(memberDb).observeRoster().first().shouldBeEmpty()
                } finally {
                    adminEngine.stopAndJoin()
                    memberEngine.stopAndJoin()
                    adminScope.cancel()
                    memberScope.cancel()
                    adminDb.close()
                    memberDb.close()
                    if (GlobalContext.getKoinApplicationOrNull() != null) {
                        GlobalContext.stopKoin()
                    }
                }
            }
        }
    })

/** Polls [predicate] until true, failing the test after [ROUND_TRIP_TIMEOUT_SECONDS]. */
private suspend fun awaitUntil(predicate: suspend () -> Boolean) {
    withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
        while (!predicate()) {
            delay(50)
        }
    }
}

/**
 * [AdminRepositoryImpl] wired to a real [ListenUpDatabase]'s roster DAO — the real
 * [com.calypsan.listenup.client.domain.repository.AdminRepository.observeRoster] consumption
 * path under test. Every other collaborator is a strict mock since `observeRoster()` never
 * touches them.
 */
private fun adminRepositoryOver(db: ListenUpDatabase) =
    AdminRepositoryImpl(
        adminUserChannel = RpcChannel.forTest(mock<AdminUserService>()),
        adminSettingsChannel = RpcChannel.forTest(mock<AdminSettingsService>()),
        inviteRpc = mock<InviteRpcFactory>(),
        libraryAdminChannel = RpcChannel.forTest(mock<LibraryAdminService>()),
        serverConfig = mock<ServerConfig>(),
        adminUserRosterDao = db.adminUserRosterDao(),
    )

private fun rosterRowFixture(id: String): AdminUserRosterSyncPayload =
    AdminUserRosterSyncPayload(
        id = id,
        email = "$id@example.com",
        displayName = "Newly Claimed",
        role = "MEMBER",
        status = "ACTIVE",
        canShare = true,
        accountCreatedAt = 1_000L,
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )

/**
 * Assembles a client [SyncEngine] wired against the full production catalog (the test only
 * drives the `admin_user_roster` and `genres` domains, but every handler is registered so
 * SSE events on other domains don't get logged as "unhandled" warnings) — this harness needs
 * no RPC/WebSocket surface and no books/collections access-gating machinery.
 */
private fun buildRosterSyncEngine(
    clientDb: ListenUpDatabase,
    httpClient: HttpClient,
    scope: CoroutineScope,
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
            httpClientProvider = { httpClient },
            // testApplication serves relative URLs in-process — an empty base URL is correct here.
            serverUrlProvider = { "" },
            store = store,
            transactionRunner = RoomTransactionRunner(clientDb),
        )
    val sseClient =
        SyncSseClient(
            serverUrlProvider = { "" },
            streamingClientProvider = { httpClient },
            state = state,
            scope = scope,
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
        )

    val digestClient = DomainDigestClient(httpClientProvider = { httpClient }, serverUrlProvider = { "" })
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
        scope = scope,
    ).also { engineRef = it }
}

/**
 * Test-only [AuthenticationProvider] registered under [JWT_PROVIDER]. Grants [adminUserId]
 * [UserRole.ADMIN] — enough to pass the `admin_user_roster` whole-domain gate (`isAdmin` in
 * [com.calypsan.listenup.server.sync.syncRoutes] accepts ROOT or ADMIN) — and every other bearer
 * token [UserRole.MEMBER]. Mirrors
 * [com.calypsan.listenup.client.data.sync.testing.withCollectionSyncEngineAgainstServer]'s
 * `CollectionTestAuthProvider`, substituting ADMIN for ROOT since this test wants to exercise the
 * ADMIN branch specifically (ROOT would pass the same gate but prove less).
 */
private class AdminRosterTestAuthProvider(
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
        val userId = bearerToken ?: MEMBER_ID
        val role = if (userId == adminUserId) UserRole.ADMIN else UserRole.MEMBER
        context.principal(
            UserPrincipal(
                userId = UserId(userId),
                sessionId = SessionId("test-session-$userId"),
                role = role,
            ),
        )
    }
}

/** Installs an [AdminRosterTestAuthProvider] under [JWT_PROVIDER]. */
private fun AuthenticationConfig.adminRosterTestAuth(adminUserId: String) {
    register(AdminRosterTestAuthProvider(AdminRosterTestAuthProvider.Config(JWT_PROVIDER, adminUserId)))
}
