package com.calypsan.listenup.client.collections

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.CollectionSummary
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import com.calypsan.listenup.client.data.local.db.CollectionDao
import com.calypsan.listenup.client.data.local.db.CollectionShareDao
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.RpcProxyCache
import com.calypsan.listenup.client.data.remote.forServer
import com.calypsan.listenup.client.data.remote.rpcCall
import com.calypsan.listenup.client.data.repository.CollectionRepositoryImpl
import com.calypsan.listenup.client.data.sync.testing.testAuth
import com.calypsan.listenup.client.di.e2e.TestServerConfig
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.api.collectionServiceScopedTo
import com.calypsan.listenup.server.api.createCollectionService
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase as ServerSqlDatabase
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.rpcguard.guard
import com.calypsan.listenup.server.services.BookRevisionTouch
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.mokkery.MockMode
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.server.Krpc as ServerKrpc
import kotlinx.rpc.krpc.ktor.server.rpc as serverRpc
import kotlinx.rpc.krpc.serialization.json.json as krpcJson
import kotlinx.rpc.registerService
import kotlinx.rpc.withService
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-module E2E for RPC transport recovery: the client's [RpcProxyCache] engine driving the
 * real `:server` [CollectionService] over a live kotlinx.rpc WebSocket that is torn down and
 * rebuilt mid-session.
 *
 * Unlike [CreateAndAddRpcE2ETest] (which uses `testApplication`) these tests own a real
 * `embeddedServer(CIO)` on a fixed port so the transport can be stopped and restarted — the only
 * way to reproduce the dead-cached-proxy the recovery engine heals.
 *
 * `runBlocking`, not `runTest`: the engine's `withTimeout` bounds run on the real clock, and the
 * tests do real WebSocket I/O — a virtual clock would auto-advance past the bound and spuriously
 * time out.
 */
class RpcReconnectE2ETest :
    FunSpec({

        /** Count of live user-created (NORMAL) collections — isolates them from lazy system singletons. */
        fun normalCollectionCount(driver: SqlDriver): Long =
            driver
                .executeQuery(
                    identifier = null,
                    sql = "SELECT count(*) FROM collections WHERE type = 'NORMAL' AND deleted_at IS NULL",
                    mapper = { cursor ->
                        cursor.next()
                        QueryResult.Value(cursor.getLong(0)!!)
                    },
                    parameters = 0,
                ).value

        /** Seed a fresh server SQLite with one library + folder + ROOT user; return (db, driver, service). */
        fun seedServer(): Triple<ServerSqlDatabase, SqlDriver, CollectionService> {
            val tmp = Files.createTempFile("listenup-reconnect-e2e-", ".db").toFile().apply { deleteOnExit() }
            DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:${tmp.absolutePath}"))
            val driver = DriverFactory().createDriver(tmp.absolutePath)
            val db = ServerSqlDatabase(driver)
            val now = System.currentTimeMillis()
            driver.execute(
                null,
                "INSERT INTO libraries(id, name, created_at, updated_at, revision) " +
                    "VALUES ('test-library', 'Test Library', $now, $now, 0)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO library_folders(id, library_id, root_path, created_at, updated_at, revision) " +
                    "VALUES ('test-folder', 'test-library', '/tmp/test-library', $now, $now, 0)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO users(id, email, email_normalized, password_hash, role, display_name, status, " +
                    "created_at, updated_at) VALUES " +
                    "('test-user', 'test-user@x', 'test-user@x', 'x', 'ROOT', 'test-user', 'ACTIVE', $now, $now)",
                0,
            )
            val bus = ChangeBus()
            val registry = SyncRegistry()
            val noopTouch =
                object : BookRevisionTouch {
                    override suspend fun touchRevision(id: BookId): AppResult<Unit> = AppResult.Success(Unit)
                }
            val service =
                createCollectionService(
                    collectionRepo = CollectionRepository(db, bus, registry, driver = driver),
                    collectionBookRepo = CollectionBookRepository(db, bus, registry, driver = driver),
                    grantRepo = CollectionGrantRepository(db, bus, registry, driver = driver),
                    bus = bus,
                    sql = db,
                    bookRevisionTouch = noopTouch,
                )
            return Triple(db, driver, service)
        }

        /**
         * Build (but don't start) a CIO server on [port] exposing [service] on `/api/rpc/authed`.
         * [wrap] can decorate the scoped service (e.g. to inject a delay) — identity by default.
         */
        fun buildServer(
            port: Int,
            service: CollectionService,
            wrap: (CollectionService) -> CollectionService = { it },
        ): EmbeddedServer<*, *> {
            val module: Application.() -> Unit = {
                install(ServerKrpc)
                install(Authentication) { testAuth(defaultUserId = "test-user") }
                routing {
                    authenticate(JWT_PROVIDER) {
                        serverRpc("/api/rpc/authed") {
                            rpcConfig { serialization { krpcJson(contractJson) } }
                            registerService<CollectionService> {
                                val principal =
                                    call.userPrincipalOrNull() ?: error("authed RPC mount reached without a principal")
                                guard(wrap(collectionServiceScopedTo(service, PrincipalProvider { principal })))
                            }
                        }
                    }
                }
            }
            return embeddedServer(CIO, port = port, host = "127.0.0.1", module = module)
        }

        fun stubClient(): HttpClient = HttpClient(OkHttp) { install(WebSockets) }

        test("a create over a dead-then-restarted connection heals with exactly one new row") {
            runBlocking {
                val (_, driver, service) = seedServer()

                var server = buildServer(0, service)
                server.start(wait = false)
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val baseUrl = "http://127.0.0.1:$port"

                val channel =
                    RpcChannel.forServer<CollectionService>(
                        apiClientFactory = StubApiClientFactory(stubClient()),
                        serverConfig = TestServerConfig(baseUrl),
                    )
                val repo =
                    CollectionRepositoryImpl(
                        collectionDao = mock<CollectionDao>(MockMode.autofill),
                        collectionBookDao = mock<CollectionBookDao>(MockMode.autofill),
                        collectionShareDao = mock<CollectionShareDao>(MockMode.autofill),
                        channel = channel,
                    )

                // First create over a live connection — caches the proxy.
                repo.create("test-library", "Staff Picks").shouldBeInstanceOf<AppResult.Success<*>>()

                // Kill the transport, then bring an identical server back on the SAME port.
                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
                server = buildServer(port, service)
                server.start(wait = false)

                // Second create hits the DEAD cached proxy → the engine reconnects and retries.
                repo.create("test-library", "Reconnected Picks").shouldBeInstanceOf<AppResult.Success<*>>()

                normalCollectionCount(driver) shouldBe 2L // no phantom dup from the retry

                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            }
        }

        test("a handler that outlasts the client bound times out without a second mutation") {
            runBlocking {
                val (_, driver, service) = seedServer()

                // The scoped service commits the row, THEN stalls past the client's bound.
                val stalling: (CollectionService) -> CollectionService = { scoped ->
                    object : CollectionService by scoped {
                        override suspend fun createCollection(
                            libraryId: String,
                            name: String,
                        ): AppResult<CollectionSummary> {
                            val committed = scoped.createCollection(libraryId, name)
                            delay(10_000) // far beyond the 2s client bound below
                            return committed
                        }
                    }
                }

                val server = buildServer(0, service, wrap = stalling)
                server.start(wait = false)
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val baseUrl = "http://127.0.0.1:$port"

                val cache =
                    RpcProxyCache(StubApiClientFactory(stubClient()), TestServerConfig(baseUrl)) { client, url ->
                        client.rpc("$url/api/rpc/authed").withService<CollectionService>()
                    }

                val result: AppResult<CollectionSummary> =
                    cache.rpcCall(timeout = 2.seconds) {
                        it.createCollection("test-library", "Staff Picks")
                    }

                result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<TransportError.Timeout>()
                // The handler committed once and the timeout path never retries — exactly one row.
                normalCollectionCount(driver) shouldBe 1L

                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            }
        }

        // THE regression guard for the reviewer's blocker: a mutation whose frame is DELIVERED and
        // committed, then loses its response to a mid-flight connection drop, must surface a Failure
        // and NOT be retried (which would double-commit). A TCP relay severs the live socket after the
        // server commits — no server restart — reproducing kotlinx.rpc's bare "Client cancelled" CE.
        test("a delivered-then-dropped create surfaces Failure with exactly one commit (no double-apply)") {
            runBlocking {
                val (_, driver, service) = seedServer()

                val committed = CompletableDeferred<Unit>()
                val invocations =
                    java.util.concurrent.atomic
                        .AtomicInteger(0)
                val severAfterCommit: (CollectionService) -> CollectionService = { scoped ->
                    object : CollectionService by scoped {
                        override suspend fun createCollection(
                            libraryId: String,
                            name: String,
                        ): AppResult<CollectionSummary> {
                            val n = invocations.incrementAndGet()
                            val result = scoped.createCollection(libraryId, name) // COMMITS the row
                            if (n == 1) {
                                // Hold ONLY the first response so the test can sever it mid-flight. A
                                // (buggy) retry would arrive as a 2nd invocation, commit again, and
                                // return immediately — proving the double-apply.
                                committed.complete(Unit)
                                delay(30_000)
                            }
                            return result
                        }
                    }
                }

                val server = buildServer(0, service, wrap = severAfterCommit)
                server.start(wait = false)
                val serverPort =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port

                // Client talks to the relay, which forwards to the real server until we cut it.
                val relay = TcpRelay.start("127.0.0.1", serverPort)
                val baseUrl = "http://127.0.0.1:${relay.port}"

                val channel =
                    RpcChannel.forServer<CollectionService>(
                        apiClientFactory = StubApiClientFactory(stubClient()),
                        serverConfig = TestServerConfig(baseUrl),
                    )
                val repo =
                    CollectionRepositoryImpl(
                        collectionDao = mock<CollectionDao>(MockMode.autofill),
                        collectionBookDao = mock<CollectionBookDao>(MockMode.autofill),
                        collectionShareDao = mock<CollectionShareDao>(MockMode.autofill),
                        channel = channel,
                    )

                val pending = async { repo.create("test-library", "Staff Picks") }
                committed.await() // the server has COMMITTED; the client is now awaiting the response
                delay(200) // let the client park on the pending response
                // Drop the in-flight connection but keep the relay ACCEPTING — so a (buggy) retry can
                // reconnect and reach the server, exposing a double-commit. The fix must not retry.
                relay.cut()

                val result = pending.await()

                // Surfaced honestly (not a phantom Success carrying a retried row's id)...
                result.shouldBeInstanceOf<AppResult.Failure>()
                // ...and committed EXACTLY once — a retry here would have double-applied the mutation.
                normalCollectionCount(driver) shouldBe 1L

                relay.shutdown()
                server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            }
        }
    })

/**
 * A dead-simple bidirectional TCP relay. Forwards bytes between a client and [targetPort].
 *
 * [cut] drops every CURRENTLY-open connection (severing a live request mid-flight) but keeps the
 * listener accepting, so a reconnect still reaches the server — the crucial detail that lets a
 * *buggy* retry expose a double-commit. [shutdown] tears the whole relay down.
 */
private class TcpRelay private constructor(
    private val listener: ServerSocket,
    private val targetHost: String,
    private val targetPort: Int,
) {
    val port: Int get() = listener.localPort
    private val liveConnections = CopyOnWriteArrayList<Closeable>()

    @Volatile private var accepting = true

    init {
        thread(isDaemon = true, name = "relay-accept") {
            while (accepting) {
                val downstream = runCatching { listener.accept() }.getOrNull() ?: break
                val upstream = runCatching { Socket(targetHost, targetPort) }.getOrNull()
                if (upstream == null) {
                    runCatching { downstream.close() }
                    continue
                }
                liveConnections.add(downstream)
                liveConnections.add(upstream)
                pump(downstream, upstream)
                pump(upstream, downstream)
            }
        }
    }

    private fun pump(
        from: Socket,
        to: Socket,
    ) {
        thread(isDaemon = true, name = "relay-pump") {
            runCatching {
                from.getInputStream().copyTo(to.getOutputStream())
                to.getOutputStream().flush()
            }
        }
    }

    /** Sever all in-flight connections; the listener stays open so a reconnect can still get through. */
    fun cut() {
        val snapshot = liveConnections.toList()
        liveConnections.clear()
        snapshot.forEach { runCatching { it.close() } }
    }

    /** Full teardown — stop accepting and close everything. */
    fun shutdown() {
        accepting = false
        cut()
        runCatching { listener.close() }
    }

    companion object {
        fun start(
            targetHost: String,
            targetPort: Int,
        ): TcpRelay = TcpRelay(ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")), targetHost, targetPort)
    }
}

/** Minimal [ApiClientFactory] serving one WebSocket-capable client; only [getClient] is exercised. */
private class StubApiClientFactory(
    private val client: HttpClient,
) : ApiClientFactory {
    override suspend fun getClient(): HttpClient = client

    override suspend fun getStreamingClient(): HttpClient = client

    override suspend fun getUnauthenticatedStreamingClient(): HttpClient = client

    override suspend fun invalidateRequestClientOnly() {}

    override suspend fun warmUp() {}

    override suspend fun invalidate() {}
}
