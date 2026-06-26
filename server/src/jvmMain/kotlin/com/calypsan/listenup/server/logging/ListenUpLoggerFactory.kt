package com.calypsan.listenup.server.logging

import org.slf4j.ILoggerFactory
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.concurrent.ConcurrentHashMap

/**
 * SLF4J [ILoggerFactory] for the ListenUp server.
 *
 * Instances are created by [ListenUpLogProvider] at initialization time and shared for
 * the lifetime of the JVM. Logger instances are cached by name; [getLogger] always
 * returns the same object for the same name.
 *
 * Level filtering delegates to [LogLevelConfig], which is built from environment variables
 * at startup. `LISTENUP_LOG_LEVEL` sets the default level; per-package overrides are
 * expressed as `LISTENUP_LOG_LEVEL_<pkg_with_underscores>`. The `io.netty.*` /
 * `org.eclipse.jetty.*` WARN floor is always enforced regardless of env config.
 *
 * @param isJsonFormat `true` → JSON one-per-line; `false` → human-readable plain text.
 * @param levelConfig  level resolution config, defaulting to INFO-everywhere if omitted.
 */
class ListenUpLoggerFactory
    internal constructor(
        val isJsonFormat: Boolean,
        private val levelConfig: LogLevelConfig = LogLevelConfig.DEFAULT,
    ) : ILoggerFactory {
    private val cache = ConcurrentHashMap<String, ListenUpLogger>()

    /** Non-null only when a test has called [installTestCapture]. */
    @Volatile
    var testCapture: TestCapture? = null
        internal set

    /**
     * When non-null, overrides the configured minimum level for every logger so that tests
     * can assert on DEBUG-level log output regardless of the configured default (INFO).
     * Set by [installTestCapture] and cleared by [removeTestCapture].
     */
    @Volatile
    internal var testMinLevel: Level? = null

    override fun getLogger(name: String): ListenUpLogger = cache.computeIfAbsent(name) { ListenUpLogger(it, this) }

    /**
     * Returns the minimum [Level] that should be emitted for [loggerName].
     *
     * When [testMinLevel] is set (i.e. a test capture is active) it takes the lower (more
     * permissive) of the override and the configured level, so that DEBUG events are visible
     * to test assertions without changing the production configuration.
     */
    fun levelFor(loggerName: String): Level {
        val override = testMinLevel
        val configured = levelConfig.levelFor(loggerName)
        return if (override != null && override.toInt() < configured.toInt()) override else configured
    }

    // ----- Test helpers -----------------------------------------------------

    companion object {
        /**
         * Installs a fresh [TestCapture] on the current factory and returns it.
         *
         * Also lowers the effective log level to [Level.DEBUG] for the duration of the capture
         * so that tests can assert on DEBUG-level output regardless of the production default.
         *
         * Must be paired with [removeTestCapture] in a `finally` block.
         */
        fun installTestCapture(): TestCapture {
            val factory =
                LoggerFactory.getILoggerFactory() as? ListenUpLoggerFactory
                    ?: error(
                        "SLF4J backend is not ListenUpLoggerFactory — " +
                            "ensure META-INF/services/org.slf4j.spi.SLF4JServiceProvider is on the test classpath.",
                    )
            val capture = TestCapture()
            factory.testCapture = capture
            factory.testMinLevel = Level.DEBUG
            return capture
        }

        /**
         * Removes any installed [TestCapture] and restores the configured log level,
         * returning the factory to normal stdout-only logging.
         */
        fun removeTestCapture() {
            val factory = LoggerFactory.getILoggerFactory() as? ListenUpLoggerFactory ?: return
            factory.testCapture = null
            factory.testMinLevel = null
        }
    }
}
