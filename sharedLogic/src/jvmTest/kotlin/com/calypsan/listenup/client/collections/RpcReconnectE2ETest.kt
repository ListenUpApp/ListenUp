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
import com.calypsan.listenup.client.data.remote.KtorCollectionRpcFactory
import com.calypsan.listenup.client.data.remote.RpcProxyCache
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.server.Krpc as ServerKrpc
import kotlinx.rpc.krpc.ktor.server.rpc as serverRpc
import kotlinx.rpc.krpc.serialization.json.json as krpcJson
import kotlinx.rpc.registerService
import kotlinx.rpc.withService
import java.nio.file.Files
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

                val factory =
                    KtorCollectionRpcFactory(
                        apiClientFactory = StubApiClientFactory(stubClient()),
                        serverConfig = TestServerConfig(baseUrl),
                    )
                val repo =
                    CollectionRepositoryImpl(
                        collectionDao = mock<CollectionDao>(MockMode.autofill),
                        collectionBookDao = mock<CollectionBookDao>(MockMode.autofill),
                        collectionShareDao = mock<CollectionShareDao>(MockMode.autofill),
                        rpcFactory = factory,
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
    })

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
