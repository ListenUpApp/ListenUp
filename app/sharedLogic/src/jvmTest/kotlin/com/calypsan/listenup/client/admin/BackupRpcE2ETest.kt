package com.calypsan.listenup.client.admin

import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.api.dto.backup.BackupSummary
import com.calypsan.listenup.api.dto.backup.RestoreResult
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.repository.BackupRepositoryImpl
import dev.mokkery.MockMode
import dev.mokkery.mock
import com.calypsan.listenup.client.data.sync.testing.testAuth
import com.calypsan.listenup.server.api.BackupServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.backup.BackupArchive
import com.calypsan.listenup.server.backup.BackupPaths
import com.calypsan.listenup.server.backup.MaintenanceState
import com.calypsan.listenup.server.backup.RestoreOrchestrator
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.DatabaseHandle
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import com.calypsan.listenup.server.rpcguard.guard
import com.calypsan.listenup.server.sync.ChangeBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.io.files.Path as IoPath
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.Krpc as ServerKrpc
import kotlinx.rpc.krpc.ktor.server.rpc as serverRpc
import kotlinx.rpc.krpc.serialization.json.json as krpcJson
import kotlinx.rpc.registerService
import kotlinx.rpc.withService

/**
 * Cross-module E2E: the client's [BackupRepositoryImpl] drives the real `:server`
 * [BackupServiceImpl] in-process over the live kotlinx.rpc WebSocket transport.
 *
 * This is the regression guard for the bug Tasks 1–2 fixed: the admin backup
 * ViewModels used to call a dead REST route that 404'd. The repository now goes
 * through the [BackupService] RPC channel on `/api/rpc/authed` — the same seam the
 * ViewModels consume. These tests prove the route is *reachable* and returns a
 * domain-typed [AppResult] rather than a transport 404.
 *
 * The wiring mirrors [com.calypsan.listenup.client.profile.ProfileE2ETest]:
 *  - [testAuth] installs a test [AuthenticationProvider] under [JWT_PROVIDER] that
 *    mints a `UserRole.ROOT` principal — so the service's admin gate passes.
 *  - The route binds the principal via `copyWith` and wraps with the KSP-generated
 *    [guard] decorator, exactly as production `RpcRoutes.kt` does.
 *  - [backupServiceProxy] opens the proxy against the harness's in-process
 *    `ws://localhost/api/rpc/authed`, wrapped in [RpcChannel.forTest], and
 *    [BackupRepositoryImpl] is driven through it.
 *
 * The server-side [BackupServiceImpl] is built over a real [BackupArchive] +
 * [BackupPaths] + [RestoreOrchestrator] in a temp home dir — the same wiring as the
 * server-side `BackupServiceTest`'s `backupTestFixture` (replicated inline here because
 * that fixture lives in `:server`'s test source set, unreachable from `:app:sharedLogic:jvmTest`).
 */
class BackupRpcE2ETest :
    FunSpec({

        /**
         * Builds a server-side [BackupServiceImpl] over a real archive + restore
         * orchestrator rooted at [homeDir], scoped to a ROOT (admin) principal binding.
         *
         * Returns the service plus its [DatabaseHandle] so the caller can close it.
         */
        fun buildBackupService(homeDir: Path): Pair<BackupServiceImpl, DatabaseHandle> {
            val dbFile = homeDir.resolve("listenup.db")
            val handle =
                DatabaseFactory.init(
                    DatabaseConfig(jdbcUrl = "jdbc:sqlite:$dbFile"),
                )
            // Seed a marker row so the db isn't empty and restore has something to bring back.
            handle.sqlDriver.execute(null, "CREATE TABLE IF NOT EXISTS restore_marker(v TEXT)", 0)
            handle.sqlDriver.execute(null, "INSERT INTO restore_marker(v) VALUES ('before-restore')", 0)

            val paths = BackupPaths(IoPath(homeDir.toString()))
            val archive =
                BackupArchive(
                    paths = paths,
                    dbHandle = handle,
                    serverId = { "srv-test" },
                    appVersion = "0.0.0-test",
                    schemaVersion = { "1" },
                    counts = { 1 to 1 },
                )
            val eventBus = MutableSharedFlow<BackupEvent>(extraBufferCapacity = 64)
            val orchestrator =
                RestoreOrchestrator(
                    paths = paths,
                    archive = archive,
                    dbHandle = handle,
                    maintenance = MaintenanceState(),
                    eventBus = eventBus,
                    changeBus = ChangeBus(),
                )
            val service =
                BackupServiceImpl(
                    paths = paths,
                    archive = archive,
                    restoreOrchestrator = orchestrator,
                    eventBus = eventBus,
                )
            return service to handle
        }

        test("client repository drives backup list/create/delete through the real RPC route") {
            val homeDir = Files.createTempDirectory("backup-rpc-e2e-")
            val (backupService, handle) = buildBackupService(homeDir)

            try {
                testApplication {
                    application {
                        install(Authentication) { testAuth(defaultUserId = "admin") }
                        install(ServerKrpc)
                        routing {
                            authenticate(JWT_PROVIDER) {
                                serverRpc("/api/rpc/authed") {
                                    rpcConfig { serialization { krpcJson(contractJson) } }
                                    registerService<BackupService> {
                                        val p = call.userPrincipalOrNull() ?: error("auth wall regression")
                                        guard(backupService.copyWith(PrincipalProvider { p }))
                                    }
                                }
                            }
                        }
                    }

                    val rpcClient = createClient { installKrpc() }

                    // Drive the flow directly in the testApplication suspend scope — do NOT wrap in
                    // runTest. The channel's `call` bounds each RPC with `withTimeout`; under runTest's
                    // virtual clock the scheduler auto-advances past that bound while the real socket
                    // I/O is still in flight, tripping a spurious TransportError.OutcomeUnknown. The plain
                    // suspend scope uses real time, so the bound never fires. (See sibling E2E tests.)
                    val repository =
                        BackupRepositoryImpl(
                            channel = RpcChannel.forTest(rpcClient.backupServiceProxy()),
                            clientFactory = mock(MockMode.autofill),
                        )

                    // list on a fresh server → Success(emptyList), NOT a transport 404.
                    val initial =
                        repository
                            .listBackups()
                            .shouldBeInstanceOf<AppResult.Success<List<BackupSummary>>>()
                            .data
                    initial.shouldBeEmpty()

                    // create → Success(BackupSummary).
                    val created =
                        repository
                            .createBackup(includeImages = false)
                            .shouldBeInstanceOf<AppResult.Success<BackupSummary>>()
                            .data
                    created.includesImages shouldBe false

                    // list now contains the created backup.
                    val afterCreate =
                        repository
                            .listBackups()
                            .shouldBeInstanceOf<AppResult.Success<List<BackupSummary>>>()
                            .data
                    afterCreate.map { it.id } shouldContain created.id

                    // delete → Success, and it's gone from a subsequent list.
                    repository.deleteBackup(created.id).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val afterDelete =
                        repository
                            .listBackups()
                            .shouldBeInstanceOf<AppResult.Success<List<BackupSummary>>>()
                            .data
                    afterDelete.map { it.id } shouldNotContain created.id
                }
            } finally {
                handle.close()
                homeDir.toFile().deleteRecursively()
            }
        }

        test("client repository restoreBackup reaches the service and returns a domain-typed result") {
            val homeDir = Files.createTempDirectory("backup-rpc-e2e-restore-")
            val (backupService, handle) = buildBackupService(homeDir)

            try {
                testApplication {
                    application {
                        install(Authentication) { testAuth(defaultUserId = "admin") }
                        install(ServerKrpc)
                        routing {
                            authenticate(JWT_PROVIDER) {
                                serverRpc("/api/rpc/authed") {
                                    rpcConfig { serialization { krpcJson(contractJson) } }
                                    registerService<BackupService> {
                                        val p = call.userPrincipalOrNull() ?: error("auth wall regression")
                                        guard(backupService.copyWith(PrincipalProvider { p }))
                                    }
                                }
                            }
                        }
                    }

                    val rpcClient = createClient { installKrpc() }

                    // Drive directly in the testApplication suspend scope — NOT runTest; the channel's
                    // `withTimeout` bound would trip under runTest's virtual clock (see first test).
                    val repository =
                        BackupRepositoryImpl(
                            channel = RpcChannel.forTest(rpcClient.backupServiceProxy()),
                            clientFactory = mock(MockMode.autofill),
                        )

                    val created =
                        repository
                            .createBackup(includeImages = false)
                            .shouldBeInstanceOf<AppResult.Success<BackupSummary>>()
                            .data

                    // Restore swaps the DB in-process. The POINT of this assertion is that the
                    // RPC route is reachable and returns a domain-typed AppResult — Success or a
                    // typed BackupError — and never a transport 404 (TransportError). The
                    // RestoreOrchestrator operates on the server fixture's own DatabaseHandle,
                    // independent of the RPC transport (auth is the test provider, no shared DB),
                    // so a successful in-process restore here does not destabilize the harness.
                    val result = repository.restoreBackup(created.id)
                    when (result) {
                        is AppResult.Success -> {
                            result.data.shouldBeInstanceOf<RestoreResult>()
                            result.data.restoredFrom shouldBe created.id
                            result.data.includedImages shouldBe false
                        }

                        is AppResult.Failure -> {
                            // A typed BackupError is acceptable (proves the route was reached and
                            // the domain produced a typed failure). A TransportError would mean the
                            // call never hit the service — that is the bug this test guards against.
                            result.error.shouldNotBeTransport()
                        }
                    }
                }
            } finally {
                handle.close()
                homeDir.toFile().deleteRecursively()
            }
        }
    })

private fun com.calypsan.listenup.api.error.AppError.shouldNotBeTransport() {
    (this is TransportError) shouldBe false
}

/**
 * Opens a [BackupService] proxy against the harness's in-process `testApplication` at
 * `ws://localhost/api/rpc/authed`, wrapped by [RpcChannel.forTest] so the repository drives the
 * real fold semantics over a real socket. No reconnect layer — these tests don't exercise it.
 */
private suspend fun HttpClient.backupServiceProxy(): BackupService =
    rpc("ws://localhost/api/rpc/authed") {
        rpcConfig { serialization { krpcJson(contractJson) } }
    }.withService<BackupService>()
