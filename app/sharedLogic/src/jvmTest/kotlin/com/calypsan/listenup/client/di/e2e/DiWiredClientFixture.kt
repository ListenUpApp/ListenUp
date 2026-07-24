package com.calypsan.listenup.client.di.e2e

import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.di.sharedModules
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.server.module
import io.ktor.server.cio.CIO
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files

/**
 * Boots a real `Application.module()` on a CIO engine on an OS-chosen port,
 * then builds the **full** client Koin graph (`sharedModules`) in an isolated
 * `koinApplication` scope — the production graph with only Room (→ in-memory)
 * and `ServerConfig` (→ [TestServerConfig]) swapped for test doubles.
 *
 * Isolated scope, not `startKoin`: no global Koin context, so tests stay
 * parallel-safe. Caller owns [close] (use Kotest `autoClose`).
 */
internal class DiWiredClientFixture private constructor(
    private val server: EmbeddedServer<*, *>,
    val koin: KoinApplication,
) : AutoCloseable {
    override fun close() {
        @Suppress("MagicNumber")
        server.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        // The Ktor Koin plugin starts the global Koin context via install(Koin).
        // server.stop() triggers the application-stop hook that calls stopKoin(), but
        // the timing is not guaranteed within the grace/timeout window. Explicitly
        // stopping here is a no-op when the hook already ran and ensures co-resident
        // tests that call startKoin() (e.g. KoinTestRuleTest) never see a stale
        // global context.
        if (GlobalContext.getKoinApplicationOrNull() != null) {
            stopKoin()
        }
        koin.close()
    }

    companion object {
        fun start(): DiWiredClientFixture {
            val tmpDb =
                Files
                    .createTempFile("listenup-koin-e2e-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }

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

            val port = runBlocking { server.engine.resolvedConnectors() }.first().port
            val baseUrl = "http://127.0.0.1:$port"

            val koin =
                koinApplication {
                    allowOverride(true)
                    modules(sharedModules + testOverrides(baseUrl))
                }

            return DiWiredClientFixture(server, koin)
        }

        /**
         * The only test doubles in the graph: an in-memory Room database and a
         * fixed-URL [ServerConfig]. Declared after `sharedModules` so they
         * override the production bindings.
         *
         * `allowOverride(true)` is set on the [koinApplication] because Koin 4.2.1's
         * [module] function has no per-module `override` parameter.
         */
        private fun testOverrides(baseUrl: String) =
            // Only ServerConfig is rerouted to TestServerConfig. The other
            // SettingsRepositoryImpl aliases (LibrarySync, LibraryPreferences,
            // PlaybackPreferences, LocalPreferences) are intentionally left
            // production-wired — this fixture only needs to redirect the server URL.
            module {
                single<ListenUpDatabase> { createInMemoryTestDatabase() }
                single<ServerConfig> { TestServerConfig(baseUrl) }
            }

        private const val JWT_SECRET_LENGTH = 32
        private const val REFRESH_PEPPER_LENGTH = 32
    }
}
