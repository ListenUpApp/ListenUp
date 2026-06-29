package com.calypsan.listenup.server

import com.calypsan.listenup.server.io.readEnv
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig

/**
 * Builds the server configuration for the Kotlin/Native runtime from environment variables. HOCON is
 * JVM-only, so Kotlin/Native cannot load `application.conf`; this reproduces the same knobs — every
 * default and `${?ENV_VAR}` override — in code. **Keep in sync with `jvmMain/resources/application.conf`.**
 *
 * The `jwt.secret` / `auth.refreshPepper` defaults are the same placeholders as `application.conf`:
 * the secret resolver treats them as "unset" and generates + persists real secrets under
 * `$LISTENUP_HOME/secrets.properties` on first boot.
 */
internal fun defaultServerConfig(): ApplicationConfig =
    MapApplicationConfig(
        "ktor.deployment.port" to (readEnv("PORT") ?: "8080"),
        "app.serverName" to (readEnv("LISTENUP_SERVER_NAME") ?: "ListenUp"),
        "database.jdbcUrl" to "",
        "auth.refreshPepper" to
            (readEnv("LISTENUP_REFRESH_PEPPER") ?: "test-pepper-change-in-production-this-must-be-32-bytes-or-more"),
        "jwt.secret" to
            (readEnv("LISTENUP_JWT_SECRET") ?: "test-jwt-secret-change-in-production-at-least-32-bytes-please"),
        "jwt.issuer" to "listenup",
        "jwt.audience" to "listenup-client",
        "registration.policy" to (readEnv("LISTENUP_REGISTRATION_POLICY") ?: "APPROVAL_QUEUE"),
        "scanner.libraryPath" to (readEnv("LISTENUP_LIBRARY_PATH") ?: ""),
        "scanner.metadataPrecedence" to (readEnv("LISTENUP_METADATA_PRECEDENCE") ?: ""),
        "scanner.embeddedCoverCacheSize" to (readEnv("LISTENUP_EMBEDDED_COVER_CACHE_SIZE") ?: "1000"),
        "seed.profile" to (readEnv("LISTENUP_SEED_PROFILE") ?: ""),
        "server.dataDirLock" to (readEnv("LISTENUP_DATA_DIR_LOCK") ?: "true"),
        "scan.rescanOnStartup" to (readEnv("LISTENUP_SCAN_RESCAN_ON_STARTUP") ?: "true"),
        "scan.periodicRescanInterval" to (readEnv("LISTENUP_SCAN_PERIODIC_RESCAN_INTERVAL") ?: "6h"),
    )
