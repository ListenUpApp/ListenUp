package com.calypsan.listenup.server

import com.calypsan.listenup.server.io.readEnv
import com.calypsan.listenup.server.logging.installNativeLogging
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_PORT = 8080

/** How long shutdown waits for queued log lines; a stdout nobody drains must not hold the exit. */
private val LOG_FLUSH_BUDGET = 2.seconds

/**
 * Kotlin/Native entry point — the native peer of the JVM `Launcher.main` (`EngineMain` + `application.conf`).
 * HOCON is JVM-only, so the configuration is built in code by [defaultServerConfig] from environment
 * variables; otherwise this boots the exact same shared [Application.module] on the native Ktor CIO
 * engine and blocks until shutdown, then writes out the queued log lines within [LOG_FLUSH_BUDGET].
 */
fun main() {
    val logOutput = installNativeLogging()
    val port = readEnv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(
        factory = CIO,
        environment = applicationEnvironment { config = defaultServerConfig() },
        configure = { connectors.add(EngineConnectorBuilder().apply { this.port = port }) },
    ) { module() }.start(wait = true)
    // The process edge: nothing is left to block but the exit itself.
    runBlocking { logOutput.closeAndDrain(LOG_FLUSH_BUDGET) }
}
