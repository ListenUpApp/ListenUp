package com.calypsan.listenup.server

import com.calypsan.listenup.server.io.readEnv
import com.calypsan.listenup.server.logging.installNativeLogging
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
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
        configure = { listenOn(port) },
    ) { module() }.start(wait = true)
    // The process edge: nothing is left to block but the exit itself.
    runBlocking { logOutput.closeAndDrain(LOG_FLUSH_BUDGET) }
}

/**
 * Listens on [port], with `SO_REUSEADDR` on. A server that stops while clients are connected leaves
 * those connections in TIME_WAIT on [port] for about a minute, and every app reconnects the instant
 * its server goes away — so without it, any restart of a live server failed to bind and the uncaught
 * EADDRINUSE aborted the process. Ktor CIO leaves it off on Kotlin/Native; the JVM turns it on by
 * default. On Linux it admits a bind over lingering connections only, never over a second listener.
 */
internal fun CIOApplicationEngine.Configuration.listenOn(port: Int) {
    connectors.add(EngineConnectorBuilder().apply { this.port = port })
    reuseAddress = true
}
