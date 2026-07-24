package com.calypsan.listenup.client.collections

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.CollectionBookDao
import com.calypsan.listenup.client.data.local.db.CollectionDao
import com.calypsan.listenup.client.data.local.db.CollectionShareDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.repository.CollectionRepositoryImpl
import com.calypsan.listenup.client.test.fake.noopOfflineEditor
import com.calypsan.listenup.client.data.sync.testing.testAuth
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
import dev.mokkery.MockMode
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.Krpc as ServerKrpc
import kotlinx.rpc.krpc.ktor.server.rpc as serverRpc
import kotlinx.rpc.krpc.serialization.json.json as krpcJson
import kotlinx.rpc.registerService
import kotlinx.rpc.withService

/**
 * Cross-module E2E: the client's [CollectionRepositoryImpl] drives the real `:server`
 * [CollectionService] in-process over the live kotlinx.rpc WebSocket transport — the exact
 * seam `CreateCollectionUseCase` → `BookMultiSelectViewModel.createCollectionAndAddBooks`
 * consumes in production.
 *
 * This is the regression guard for the "create collection/shelf from the bulk picker is a
 * silent no-op" bug (found on device 2026-07-06, PR #1055). The create flow returns a complex
 * object (`CollectionSummary`) where add returns `Unit`; add-to-existing was proven working,
 * so the create RPC round-trip is the suspect. No test exercised this seam before — that
 * coverage gap is why the bug shipped.
 *
 * Wiring mirrors [com.calypsan.listenup.client.admin.BackupRpcE2ETest]: [testAuth] mints a
 * ROOT principal under [JWT_PROVIDER]; the route scopes the service per-request via
 * [collectionServiceScopedTo] and wraps it with the KSP-generated [guard] decorator, exactly
 * as production `RpcRoutes` does; a real [CollectionService] proxy over the in-process
 * `ws://localhost/api/rpc/authed`, wrapped in [RpcChannel.forTest], backs the repository.
 */
class CreateAndAddRpcE2ETest :
    FunSpec({

        test("client repository create() drives the real CollectionService RPC and returns Success") {
            val tmp =
                Files.createTempFile("listenup-createadd-e2e-", ".db").toFile().apply { deleteOnExit() }
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
            serverDriver.execute(
                null,
                "INSERT INTO users(id, email, email_normalized, password_hash, role, display_name, status, " +
                    "created_at, updated_at) VALUES " +
                    "('test-user', 'test-user@x', 'test-user@x', 'x', 'ROOT', 'test-user', 'ACTIVE', $now, $now)",
                0,
            )

            val collectionRepo = CollectionRepository(serverSqlDb, bus, syncRegistry, driver = serverDriver)
            val collectionBookRepo = CollectionBookRepository(serverSqlDb, bus, syncRegistry, driver = serverDriver)
            val grantRepo = CollectionGrantRepository(serverSqlDb, bus, syncRegistry, driver = serverDriver)
            val noopRevisionTouch =
                object : BookRevisionTouch {
                    override suspend fun touchRevision(id: BookId): AppResult<Unit> = AppResult.Success(Unit)
                }
            val collectionService =
                createCollectionService(
                    collectionRepo = collectionRepo,
                    collectionBookRepo = collectionBookRepo,
                    grantRepo = grantRepo,
                    bus = bus,
                    sql = serverSqlDb,
                    bookRevisionTouch = noopRevisionTouch,
                )

            testApplication {
                application {
                    install(ServerKrpc)
                    install(Authentication) { testAuth(defaultUserId = "test-user") }
                    routing {
                        authenticate(JWT_PROVIDER) {
                            serverRpc("/api/rpc/authed") {
                                rpcConfig { serialization { krpcJson(contractJson) } }
                                registerService<CollectionService> {
                                    val principal =
                                        call.userPrincipalOrNull() ?: error("authed RPC mount reached without a principal")
                                    guard(collectionServiceScopedTo(collectionService, PrincipalProvider { principal }))
                                }
                            }
                        }
                    }
                }

                val rpcClient = createClient { installKrpc() }
                val collectionServiceProxy =
                    rpcClient
                        .rpc("ws://localhost/api/rpc/authed") {
                            rpcConfig { serialization { krpcJson(contractJson) } }
                        }.withService<CollectionService>()
                val repository =
                    CollectionRepositoryImpl(
                        collectionDao = mock<CollectionDao>(MockMode.autofill),
                        collectionBookDao = mock<CollectionBookDao>(MockMode.autofill),
                        collectionShareDao = mock<CollectionShareDao>(MockMode.autofill),
                        channel = RpcChannel.forTest(collectionServiceProxy),
                        offlineEditor = noopOfflineEditor(),
                    )

                // runBlocking (real wall-clock), not runTest: the repo's channel.call now bounds each call
                // with withTimeout, whose clock is virtual under runTest — runTest would auto-advance
                // past the 15s bound before the real WebSocket I/O completes and spuriously time out.
                runBlocking {
                    // The decisive probe: does the create RPC (complex `CollectionSummary` return)
                    // round-trip over the real transport and map to a Success? A silent no-op on the
                    // client would surface here as a Failure.
                    val created = repository.create("test-library", "Staff Picks")
                    created.shouldBeInstanceOf<AppResult.Success<*>>()
                }
            }
        }
    })
