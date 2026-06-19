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
 * Level filtering is prefix-based:
 * - `io.netty.*` → [Level.WARN]
 * - `org.eclipse.jetty.*` → [Level.WARN]
 * - everything else → [Level.INFO]
 *
 * @param isJsonFormat `true` → JSON one-per-line; `false` → human-readable plain text.
 */
class ListenUpLoggerFactory(
    val isJsonFormat: Boolean,
) : ILoggerFactory {
    private val cache = ConcurrentHashMap<String, ListenUpLogger>()

    /** Non-null only when a test has called [installTestCapture]. */
    @Volatile
    var testCapture: TestCapture? = null
        internal set

    override fun getLogger(name: String): ListenUpLogger = cache.computeIfAbsent(name) { ListenUpLogger(it, this) }

    /**
     * Returns the minimum [Level] that should be emitted for [loggerName].
     *
     * Matching is prefix-based so sub-packages are suppressed automatically.
     */
    fun levelFor(loggerName: String): Level =
        when {
            loggerName.startsWith("io.netty") -> Level.WARN
            loggerName.startsWith("org.eclipse.jetty") -> Level.WARN
            else -> Level.INFO
        }

    // ----- Test helpers -----------------------------------------------------

    companion object {
        /**
         * Installs a fresh [TestCapture] on the current factory and returns it.
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
            return capture
        }

        /**
         * Removes any installed [TestCapture], returning the factory to normal stdout-only logging.
         */
        fun removeTestCapture() {
            val factory = LoggerFactory.getILoggerFactory() as? ListenUpLoggerFactory ?: return
            factory.testCapture = null
        }
    }
}
