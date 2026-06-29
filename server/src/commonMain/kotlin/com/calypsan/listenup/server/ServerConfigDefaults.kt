package com.calypsan.listenup.server

/**
 * One server configuration knob: its [key], its [default] value, and the optional [envVar] that
 * overrides it. The single source of truth for the server's configuration defaults — shared by the
 * Kotlin/Native [defaultServerConfig] (which builds the config in code, HOCON being JVM-only) and the
 * JVM runtime's `jvmMain/resources/application.conf`. `ServerConfigDefaultsContractTest` (jvmTest)
 * fails the build if `application.conf` ever drifts from this list.
 */
internal data class ServerConfigDefault(
    val key: String,
    val default: String,
    val envVar: String?,
)

/**
 * The canonical server configuration defaults. Adding or changing a knob here is the *only* place a
 * default lives; `application.conf` must mirror it (pinned by the contract test) and the native config
 * is built straight from it.
 */
internal val SERVER_CONFIG_DEFAULTS: List<ServerConfigDefault> =
    listOf(
        ServerConfigDefault("ktor.deployment.port", "8080", "PORT"),
        ServerConfigDefault("app.serverName", "ListenUp", "LISTENUP_SERVER_NAME"),
        ServerConfigDefault("database.jdbcUrl", "", null),
        // Placeholder secrets: the resolver treats these exact defaults as "unset" and generates real
        // secrets under $LISTENUP_HOME/secrets.properties on first boot.
        ServerConfigDefault(
            "auth.refreshPepper",
            "test-pepper-change-in-production-this-must-be-32-bytes-or-more",
            "LISTENUP_REFRESH_PEPPER",
        ),
        ServerConfigDefault(
            "jwt.secret",
            "test-jwt-secret-change-in-production-at-least-32-bytes-please",
            "LISTENUP_JWT_SECRET",
        ),
        ServerConfigDefault("jwt.issuer", "listenup", null),
        ServerConfigDefault("jwt.audience", "listenup-client", null),
        ServerConfigDefault("registration.policy", "APPROVAL_QUEUE", "LISTENUP_REGISTRATION_POLICY"),
        ServerConfigDefault("scanner.libraryPath", "", "LISTENUP_LIBRARY_PATH"),
        ServerConfigDefault("scanner.metadataPrecedence", "", "LISTENUP_METADATA_PRECEDENCE"),
        ServerConfigDefault("scanner.embeddedCoverCacheSize", "1000", "LISTENUP_EMBEDDED_COVER_CACHE_SIZE"),
        ServerConfigDefault("seed.profile", "", "LISTENUP_SEED_PROFILE"),
        ServerConfigDefault("server.dataDirLock", "true", "LISTENUP_DATA_DIR_LOCK"),
        ServerConfigDefault("scan.rescanOnStartup", "true", "LISTENUP_SCAN_RESCAN_ON_STARTUP"),
        ServerConfigDefault("scan.periodicRescanInterval", "6h", "LISTENUP_SCAN_PERIODIC_RESCAN_INTERVAL"),
    )
