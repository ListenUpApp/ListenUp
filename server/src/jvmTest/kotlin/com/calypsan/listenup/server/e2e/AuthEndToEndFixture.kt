package com.calypsan.listenup.server.e2e

import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.client.di.clientApiClientFactoryTestModule
import com.calypsan.listenup.client.di.clientAuthModuleForTests
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.server.module
import io.ktor.server.cio.CIO
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.koin.core.KoinApplication
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files

/**
 * Fixture that boots a real `Application.module()` on a CIO engine listening on
 * an OS-chosen port, then resolves the real client `AuthRepository` from a
 * Koin scope wired with the production [clientAuthModule] plus a small test
 * infra module supplying the platform leaves the auth flow needs:
 *
 * | Dependency             | Strategy                                                 |
 * |------------------------|----------------------------------------------------------|
 * | `Application.module()` | Real, against a fresh tmp SQLite file                    |
 * | `AuthSession`          | Real `AuthSessionStore` from `clientAuthModule`          |
 * | `AuthRepository`       | Real `AuthRepositoryImpl` from `clientAuthModule`        |
 * | `AuthRpcFactory`       | Real, talking over the bearer-equipped `ApiClientFactory`|
 * |                        | client + `installKrpc` — the production graph            |
 * | `ApiClientFactory`     | Real, bearer + retry + HttpSend wired against the test   |
 * |                        | server URL                                               |
 * | `SecureStorage`        | [InMemorySecureStorage]                                  |
 * | `ServerConfig`         | [TestServerConfig] returning the embedded URL            |
 * | `InstanceRepository`   | [StubInstanceRepository] — auth flow doesn't read it     |
 * | `UserRepository` /     | Not bound — `LoginUseCase` / `LogoutUseCase` aren't      |
 * | `PlaybackManager`      | resolved here; tests call `AuthRepository` direct        |
 *
 * The test exercises the full client→server round-trip, including kotlinx.rpc
 * serialization, JWT signing, refresh-token rotation, and Exposed/SQLite
 * persistence — the full contract boundary, with NO test-only overrides on
 * the contract path. Anything that breaks here breaks production identically.
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
                            "mdns.enabled" to "false",
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
                        clientAuthModuleForTests(),
                        clientApiClientFactoryTestModule(),
                        testInfraModule(baseUrl),
                    )
                }

            return AuthEndToEndFixture(server, koin)
        }

        private fun testInfraModule(baseUrl: String) =
            module {
                single<SecureStorage> { InMemorySecureStorage() }
                single<ServerConfig> { TestServerConfig(baseUrl) }
                single<InstanceRepository> { StubInstanceRepository() }
                // AuthSessionStore observes the registration-policy stream on the app scope; the
                // production binding lives in appCoreModule, which this minimal fixture omits.
                single<CoroutineScope>(qualifier = named("appScope")) {
                    CoroutineScope(SupervisorJob() + Dispatchers.Default)
                }
                // ApiClientFactory is bound by `clientApiClientFactoryTestModule()` (in :sharedLogic
                // jvmMain) — the type is internal to :sharedLogic so it can't be bound from here.
                // `UserRepository` and `PlaybackManager` are only needed by
                // `LoginUseCase` / `LogoutUseCase` (factory bindings in
                // `clientAuthModule`). These tests call `AuthRepository` directly and
                // never resolve the use cases, so omitting these bindings
                // keeps the test surface minimal.
            }

        private const val JWT_SECRET_LENGTH = 32
        private const val REFRESH_PEPPER_LENGTH = 32
    }
}
