package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.module
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import com.calypsan.listenup.api.AuthServicePublic
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import com.calypsan.listenup.api.ScannerService

/**
 * Fixture that boots a real `Application.module()` against a temp library
 * directory, returning an [HttpClient] bound to the embedded server's
 * resolved port. Mirrors the pattern from `AuthEndToEndFixture`: NO
 * test-side overrides on the production graph — anything that breaks here
 * breaks production identically.
 *
 * The returned [client] is ROOT-authenticated: on boot, this fixture mints a
 * fresh ROOT account via `AuthServicePublic.setupRoot` and attaches the resulting
 * access token to every request via `defaultRequest`, so scanner tests can
 * exercise the now-JWT-gated `/api/v1/scan*` surface without minting tokens
 * themselves.
 *
 * Use [populate] to seed the library before the server boots. The boot
 * triggers an initial full scan via `bootstrapScannerOnStartup` so by the
 * time the fixture is returned, [HttpClient] requests can race the
 * bootstrap scan — tests should account for that (e.g. poll
 * `ScannerService.lastScanResult`, or call `scanFull()` and accept that the
 * first call may return `AlreadyRunning` while bootstrap is still running).
 */
internal class ScannerEndToEndFixture private constructor(
    val libraryRoot: Path,
    private val server: EmbeddedServer<*, *>,
    val client: HttpClient,
    val baseUrl: String,
) : AutoCloseable {
    /** An authed [ScannerService] proxy against the live server — the transport the app uses. */
    suspend fun scannerService(): ScannerService =
        client
            .rpc(baseUrl.replace("http://", "ws://") + "/api/rpc/authed") {
                rpcConfig { serialization { json(contractJson) } }
            }.withService<ScannerService>()

    override fun close() {
        client.close()
        @Suppress("MagicNumber")
        server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        libraryRoot.toFile().deleteRecursively()
    }

    companion object {
        fun start(populate: AudioLibraryFixture.() -> Unit = {}): ScannerEndToEndFixture {
            val tmpDb = Files.createTempFile("listenup-scanner-e2e-", ".db").toFile().apply { deleteOnExit() }
            val libraryRoot = Files.createTempDirectory("listenup-scanner-e2e-lib-")
            // Use AudioLibraryFixture to seed; we keep ownership of libraryRoot
            // (closing the AudioLibraryFixture would delete it before the server
            // even starts).
            AudioLibraryFixture(libraryRoot).apply(populate)

            val env =
                applicationEnvironment {
                    config =
                        MapApplicationConfig(
                            "database.jdbcUrl" to "jdbc:sqlite:${tmpDb.absolutePath}",
                            "auth.refreshPepper" to "x".repeat(REFRESH_PEPPER_LENGTH),
                            "jwt.secret" to "x".repeat(JWT_SECRET_LENGTH),
                            "jwt.issuer" to "listenup",
                            "jwt.audience" to "listenup-client",
                            "registration.policy" to "OPEN",
                            "mdns.enabled" to "false",
                            "scanner.libraryPath" to libraryRoot.toString(),
                        )
                }

            val server =
                embeddedServer(
                    factory = ServerCIO,
                    environment = env,
                    configure = {
                        connectors.add(
                            EngineConnectorBuilder().apply {
                                host = "127.0.0.1"
                                port = 0
                            },
                        )
                    },
                ) {
                    module()
                }
            server.start(wait = false)

            val resolvedPort = runBlocking { server.engine.resolvedConnectors() }.first().port
            val baseUrl = "http://127.0.0.1:$resolvedPort"

            val setupClient =
                HttpClient(CIO) {
                    install(ContentNegotiation) { json(contractJson) }
                    install(WebSockets)
                    installKrpc()
                }
            val accessToken =
                runBlocking {
                    setupClient
                        .rpc("ws://127.0.0.1:$resolvedPort/api/rpc/public") {
                            rpcConfig { serialization { json(contractJson) } }
                        }.withService<AuthServicePublic>()
                        .setupRoot(RegisterRequest("root@scanner.test", "x".repeat(MIN_PASSWORD_LENGTH), "Root"))
                        .let { it as? AppResult.Success ?: error("fixture setup-root failed: $it") }
                        .data.accessToken.value
                }.also { setupClient.close() }

            val client =
                HttpClient(CIO) {
                    install(ContentNegotiation) { json(contractJson) }
                    install(WebSockets)
                    installKrpc()
                    install(HttpTimeout) {
                        requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    }
                    defaultRequest { bearerAuth(accessToken) }
                }

            return ScannerEndToEndFixture(libraryRoot, server, client, baseUrl)
        }

        private const val JWT_SECRET_LENGTH = 32
        private const val REFRESH_PEPPER_LENGTH = 32
        private const val REQUEST_TIMEOUT_MS = 10_000L
        private const val MIN_PASSWORD_LENGTH = 8
    }
}
