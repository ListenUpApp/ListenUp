package com.calypsan.listenup.server.collections

import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.api.collectionServiceScopedTo
import com.calypsan.listenup.server.api.createCollectionService
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.DatabaseConfig
import com.calypsan.listenup.server.db.DatabaseFactory
import com.calypsan.listenup.server.db.sqldelight.DriverFactory
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase as ServerSqlDatabase
import com.calypsan.listenup.server.foundation.FoundationDeps
import com.calypsan.listenup.server.foundation.foundationServer
import com.calypsan.listenup.server.io.readEnv
import com.calypsan.listenup.server.rpcguard.CollectionServiceGuarded
import com.calypsan.listenup.server.services.BookRevisionTouch
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.routing.routing
import kotlin.random.Random
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.registerService
import kotlinx.rpc.withService

/**
 * Native (linuxX64) proof for the create-collection RPC — the exact Kotlin/Native kotlinx.rpc
 * transport that `server.kexe` and the iOS client run, which the JVM harness cannot exercise.
 *
 * Regression guard for the "create collection/shelf from the bulk picker is a silent no-op"
 * bug (found on device 2026-07-06, PR #1055). The JVM RPC E2E
 * ([com.calypsan.listenup.client.collections.CreateAndAddRpcE2ETest]) proved the create path
 * works end-to-end over the JVM transport, isolating the defect to the Kotlin/Native stack.
 * This drives the real native [CollectionService] (over a real native SQLite DB) across the
 * native CIO/krpc WebSocket transport — if the create RPC (complex `CollectionSummary` return)
 * fails on native, it reproduces HERE, on Linux, where it can be fixed.
 *
 * Structure mirrors [com.calypsan.listenup.server.rpcguard.RpcGuardNativeTest]: `kotlin.test.@Test`
 * + `runBlocking` (Kotest's FunSpec is invisible to the K/N test runner), a real [foundationServer]
 * over the native CIO engine, and a native CIO/krpc client opening the service proxy.
 */
class CollectionCreateNativeRpcTest {
    private val jwt = JwtConfiguration("x".repeat(JWT_SECRET_LEN), "listenup", "listenup-client")

    @Test
    fun createCollectionRoundTripsOverNativeKrpc(): Unit =
        runBlocking {
            // A real, writable, ABSOLUTE dir under $HOME (the native test runner has no usable temp
            // dir — see NativeServerBootTest). Absolute so MigrationRunner's admin connection and
            // SQLiter's driver resolve to the SAME file: a bare filename sends SQLiter to its default
            // dir while the admin connection opens it cwd-relative, and the two never meet
            // (DriverFactory.linux splits name/basePath).
            val home = readEnv("HOME")?.takeIf { it.isNotBlank() } ?: "."
            val dir = "$home/.lu-collection-native-test"
            SystemFileSystem.createDirectories(Path(dir))
            val dbPath = "$dir/create-${Random.nextInt(1, Int.MAX_VALUE).toString(HEX_RADIX)}.db"
            DatabaseFactory.init(DatabaseConfig(jdbcUrl = "jdbc:sqlite:$dbPath"))
            val driver = DriverFactory().createDriver(dbPath)
            val db = ServerSqlDatabase(driver)
            val bus = ChangeBus()
            val registry = SyncRegistry()

            // FK targets for the collection insert + owner principal.
            driver.execute(
                null,
                "INSERT INTO libraries(id, name, created_at, updated_at, revision) " +
                    "VALUES ('test-library', 'Test Library', 1, 1, 0)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO library_folders(id, library_id, root_path, created_at, updated_at, revision) " +
                    "VALUES ('test-folder', 'test-library', '/tmp/test-library', 1, 1, 0)",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO users(id, email, email_normalized, password_hash, role, display_name, status, " +
                    "created_at, updated_at) VALUES " +
                    "('admin', 'admin@x', 'admin@x', 'x', 'ROOT', 'admin', 'ACTIVE', 1, 1)",
                0,
            )

            val collectionRepo = CollectionRepository(db, bus, registry, driver = driver)
            val collectionBookRepo = CollectionBookRepository(db, bus, registry, driver = driver)
            val grantRepo = CollectionGrantRepository(db, bus, registry, driver = driver)
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
                    sql = db,
                    bookRevisionTouch = noopRevisionTouch,
                )

            val server =
                foundationServer(port = 0, deps = FoundationDeps(jwt) { true }) {
                    routing {
                        rpc("/api/rpc/public") {
                            rpcConfig { serialization { json(contractJson) } }
                            registerService<CollectionService> {
                                CollectionServiceGuarded(
                                    collectionServiceScopedTo(
                                        collectionService,
                                        PrincipalProvider {
                                            UserPrincipal(UserId("admin"), SessionId("s-admin"), UserRole.ROOT)
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            server.start(wait = false)
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val rpcClient =
                    HttpClient(ClientCIO) {
                        install(ClientWebSockets)
                        installKrpc()
                    }
                try {
                    val collections =
                        rpcClient
                            .rpc("ws://127.0.0.1:$port/api/rpc/public") {
                                rpcConfig { serialization { json(contractJson) } }
                            }.withService<CollectionService>()

                    // The decisive native probe: does the create RPC (complex `CollectionSummary`
                    // return) round-trip over the native krpc transport and yield a Success?
                    val result = collections.createCollection("test-library", "Staff Picks")
                    val created = result.shouldBeInstanceOf<AppResult.Success<*>>()
                    created.data.shouldBeInstanceOf<com.calypsan.listenup.api.dto.CollectionSummary>()
                } finally {
                    rpcClient.close()
                }
            } finally {
                server.stop(0, 0)
                driver.close()
                listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach {
                    SystemFileSystem.delete(Path(it), mustExist = false)
                }
            }
        }

    private companion object {
        const val JWT_SECRET_LEN = 32
        const val HEX_RADIX = 16
    }
}
