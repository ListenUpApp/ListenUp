package com.calypsan.listenup.server

import com.calypsan.listenup.server.io.readEnv
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig

/**
 * Builds the server configuration for the Kotlin/Native runtime. HOCON is JVM-only, so Kotlin/Native
 * cannot load `application.conf`; instead the config is assembled in code from the shared
 * [SERVER_CONFIG_DEFAULTS] — each knob's env-var override wins, falling back to its default. The
 * canonical defaults live in one place (`ServerConfigDefaults`); `application.conf` mirrors them and is
 * pinned by `ServerConfigDefaultsContractTest`.
 */
internal fun defaultServerConfig(): ApplicationConfig {
    val values =
        SERVER_CONFIG_DEFAULTS
            .map { entry -> entry.key to (entry.envVar?.let(::readEnv) ?: entry.default) }
            .toTypedArray()
    return MapApplicationConfig(*values)
}
