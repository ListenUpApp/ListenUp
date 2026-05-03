package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.client.core.SecureStorage
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.AuthRpcFactory
import com.calypsan.listenup.client.di.clientAuthModule
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.server.module
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.cio.CIO
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.serialization.json.json
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files

/**
 * Fixture that boots a real `Application.module()` on a CIO engine listening on
 * an OS-chosen port, then wires a client-side Koin scope around `clientAuthModule`
 * with test-side substitutes for every transitive dependency:
 *
 * | Dependency             | Strategy                                                 |
 * |------------------------|----------------------------------------------------------|
 * | `Application.module()` | Real, against a fresh tmp SQLite file                    |
 * | `AuthSession`          | Real `AuthSessionStore` from `clientAuthModule`          |
 * | `AuthRepository`       | Real `AuthRepositoryImpl` from `clientAuthModule`        |
 * | `AuthRpcFactory`       | [TestAuthRpcFactory] subclass — overrides `rpcClient()`  |
 * |                        | + `requireBaseUrl()` to sidestep two production bugs F12 |
 * |                        | uncovered (see binding comment + `TestAuthRpcFactory`).  |
 * | `ApiClientFactory`     | Real (constructed by Koin) — present for the auth        |
 * |                        | refresh seam wiring, not exercised by tests.             |
 * | `SecureStorage`        | [InMemorySecureStorage]                                  |
 * | `ServerConfig`         | [TestServerConfig] returning the embedded URL            |
 * | `InstanceRepository`   | [StubInstanceRepository] — auth flow doesn't read it     |
 * | `UserRepository` /     | Not bound — `LoginUseCase`/`LogoutUseCase` aren't        |
 * | `PlaybackManager`      | resolved by F12; the tests call `AuthRepository` direct  |
 *
 * The test exercises the full client→server round-trip, including kotlinx.rpc
 * serialization, JWT signing, refresh-token rotation, and Exposed/SQLite
 * persistence — the full contract boundary.
 */
internal class AuthEndToEndFixture private constructor(
    private val server: EmbeddedServer<*, *>,
    private val koin: KoinApplication,
) : AutoCloseable {
    val authRepository: AuthRepository = koin.koin.get()
    val authSession: AuthSession = koin.koin.get()

    override fun close() {
        @Suppress("MagicNumber")
        server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        koin.close()
    }

    companion object {
        /**
         * Boot a fresh server + client graph. Caller is responsible for [close]
         * (or use Kotest's `autoClose`). Each call gets its own SQLite file +
         * port so tests can run in parallel without bleeding state.
         */
        fun start(): AuthEndToEndFixture {
            val tmpDb = Files.createTempFile("listenup-e2e-", ".db").toFile().apply { deleteOnExit() }

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
                        )
                }

            val server =
                embeddedServer(
                    factory = CIO,
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

            val resolvedPort =
                runBlocking { server.engine.resolvedConnectors() }.first().port
            val baseUrl = "http://127.0.0.1:$resolvedPort"

            val koin =
                koinApplication {
                    modules(
                        clientAuthModule,
                        testInfraModule(baseUrl),
                    )
                }

            return AuthEndToEndFixture(server, koin)
        }

        private fun testInfraModule(baseUrl: String) =
            module {
                // Bindings the auth flow actually exercises.
                single<SecureStorage> { InMemorySecureStorage() }
                single<ServerConfig> { TestServerConfig(baseUrl) }
                single<InstanceRepository> { StubInstanceRepository() }
                single<ApiClientFactory> {
                    ApiClientFactory(
                        serverConfig = get(),
                        authSession = get(),
                        refreshAccessToken = { get<AuthRepository>().refreshAccessToken() },
                    )
                }
                // Override `clientAuthModule`'s `AuthRpcFactory` binding with a
                // test subclass that uses a clean CIO `HttpClient` with `installKrpc`.
                // Production's `ApiClientFactory.HttpSend` interceptor rewrites the
                // request URL before kotlinx.rpc's WebSocket upgrade can negotiate,
                // breaking the rpc layer end-to-end. Tracked as an F12-discovered
                // production bug; until fixed, the test pins the contract via this
                // narrow override.
                single<AuthRpcFactory> {
                    val authSession: AuthSession = get()
                    val rpcHttpClient =
                        HttpClient(ClientCIO) {
                            install(WebSockets)
                            // Authed RPC mount expects a Bearer token on the WebSocket
                            // upgrade request. `loadTokens` reads from `AuthSession` —
                            // populated by the test's `bootstrap()` helper.
                            install(Auth) {
                                bearer {
                                    loadTokens {
                                        val access = authSession.getAccessToken()?.value
                                        val refresh = authSession.getRefreshToken()?.value
                                        if (access != null && refresh != null) {
                                            BearerTokens(accessToken = access, refreshToken = refresh)
                                        } else {
                                            null
                                        }
                                    }
                                    // No refreshTokens — the test exercises refresh
                                    // explicitly via `AuthRepository.refreshAccessToken()`.
                                    sendWithoutRequest { true }
                                }
                            }
                            installKrpc { serialization { json(contractJson) } }
                        }
                    TestAuthRpcFactory(
                        apiClientFactory = get(),
                        serverConfig = get(),
                        rpcHttpClient = rpcHttpClient,
                        wsBaseUrl = baseUrl.replaceFirst("http://", "ws://"),
                    )
                }
                // `UserRepository` and `PlaybackManager` are only needed by
                // `LoginUseCase` / `LogoutUseCase` (factory bindings in
                // `clientAuthModule`). F12 calls `AuthRepository` directly and
                // never resolves the use cases, so omitting these bindings
                // keeps the test surface minimal.
            }

        private const val JWT_SECRET_LENGTH = 32
        private const val REFRESH_PEPPER_LENGTH = 32
    }
}

/**
 * Test subclass of [AuthRpcFactory] — overrides:
 *  - `rpcClient()` to return a hand-built CIO client with [installKrpc] +
 *    explicit `WebSockets` plugin, sidestepping `ApiClientFactory`'s
 *    `HttpSend` interceptor.
 *  - `requireBaseUrl()` to return a `ws://` URL — kotlinx.rpc 0.10.x does
 *    not auto-upgrade `http://` to the WebSocket scheme. See `PluginSmokeTest`
 *    for the working pattern.
 *
 * Both overrides surface production bugs in the AuthRpcFactory ↔ ApiClientFactory
 * seam that F12 is the first test to exercise. Tracked for a follow-up fix.
 */
private class TestAuthRpcFactory(
    apiClientFactory: ApiClientFactory,
    serverConfig: ServerConfig,
    private val rpcHttpClient: HttpClient,
    private val wsBaseUrl: String,
) : AuthRpcFactory(apiClientFactory, serverConfig) {
    override suspend fun rpcClient(): HttpClient = rpcHttpClient

    override suspend fun requireBaseUrl(): String = wsBaseUrl
}
