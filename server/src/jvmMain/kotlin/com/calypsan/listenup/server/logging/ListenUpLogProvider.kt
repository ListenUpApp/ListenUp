package com.calypsan.listenup.server.logging

import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.BasicMDCAdapter
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.SLF4JServiceProvider

/**
 * SLF4J 2.x service provider for ListenUp.
 *
 * Registered via `META-INF/services/org.slf4j.spi.SLF4JServiceProvider` for discovery
 * by the SLF4J [org.slf4j.LoggerFactory] ServiceLoader.
 *
 * Format selection: reads `LISTENUP_LOG_FORMAT` environment variable at [initialize] time.
 * Value `json` (case-insensitive) → JSON; anything else → plain text.
 *
 * Level selection: reads `LISTENUP_LOG_LEVEL` (default `INFO`). Per-package overrides are
 * expressed as `LISTENUP_LOG_LEVEL_<pkg_with_underscores>` (e.g.
 * `LISTENUP_LOG_LEVEL_com_calypsan_listenup_server_sync=DEBUG`). See [LogLevelConfig].
 *
 * MDC: delegates to SLF4J's built-in [org.slf4j.helpers.BasicMDCAdapter] so that
 * `kotlinx-coroutines-slf4j` can propagate correlation IDs across coroutine boundaries
 * without any custom adapter.
 */
class ListenUpLogProvider : SLF4JServiceProvider {
    // SLF4J guarantees initialize() is called before getLoggerFactory(). The field is
    // reassigned exactly once; a nullable var avoids the LateinitUsage detekt rule while
    // keeping the same lifecycle guarantee.
    private var loggerFactory: ListenUpLoggerFactory? = null
    private val markerFactory: IMarkerFactory = BasicMarkerFactory()
    private val mdcAdapter: MDCAdapter = BasicMDCAdapter()

    override fun getRequestedApiVersion(): String = "2.0.99"

    override fun getMarkerFactory(): IMarkerFactory = markerFactory

    override fun getMDCAdapter(): MDCAdapter = mdcAdapter

    override fun getLoggerFactory(): ILoggerFactory =
        checkNotNull(loggerFactory) { "ListenUpLogProvider.initialize() has not been called" }

    override fun initialize() {
        val env = System.getenv()
        val isJson = env["LISTENUP_LOG_FORMAT"].equals("json", ignoreCase = true)
        loggerFactory = ListenUpLoggerFactory(isJsonFormat = isJson, levelConfig = LogLevelConfig.fromEnv(env))
    }
}
