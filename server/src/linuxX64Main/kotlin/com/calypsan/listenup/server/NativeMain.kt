package com.calypsan.listenup.server

import com.calypsan.listenup.server.io.readEnv
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer

private const val DEFAULT_PORT = 8080

/**
 * Kotlin/Native entry point — the native peer of the JVM `Launcher.main` (`EngineMain` + `application.conf`).
 * HOCON is JVM-only, so the configuration is built in code by [defaultServerConfig] from environment
 * variables; otherwise this boots the exact same shared [Application.module] on the native Ktor CIO
 * engine and blocks until shutdown.
 */
fun main() {
    val port = readEnv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(
        factory = CIO,
        environment = applicationEnvironment { config = defaultServerConfig() },
        configure = { connectors.add(EngineConnectorBuilder().apply { this.port = port }) },
    ) { module() }.start(wait = true)
}
