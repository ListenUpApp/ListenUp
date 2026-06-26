package com.calypsan.listenup.server.logging

import org.slf4j.event.Level

/**
 * Resolves the minimum emitted [Level] for a logger name, from environment configuration.
 *
 * Precedence (first match wins):
 * 1. `io.netty.*` / `org.eclipse.jetty.*` → [Level.WARN] (noise floor, always).
 * 2. The longest matching `LISTENUP_LOG_LEVEL_<prefix>` override.
 * 3. The `LISTENUP_LOG_LEVEL` default (or [Level.INFO] when unset/unparseable).
 */
internal class LogLevelConfig(
    private val defaultLevel: Level,
    private val overrides: List<Pair<String, Level>>,
) {
    fun levelFor(loggerName: String): Level =
        when {
            loggerName.startsWith("io.netty") -> Level.WARN
            loggerName.startsWith("org.eclipse.jetty") -> Level.WARN
            else -> overrides.firstOrNull { loggerName.startsWith(it.first) }?.second ?: defaultLevel
        }

    companion object {
        /** INFO everywhere, no overrides — preserves the pre-config behaviour. */
        val DEFAULT = LogLevelConfig(Level.INFO, emptyList())

        private const val LEVEL_KEY = "LISTENUP_LOG_LEVEL"
        private const val PREFIX_KEY = "LISTENUP_LOG_LEVEL_"

        /**
         * Builds a config from an environment map. `LISTENUP_LOG_LEVEL` sets the default;
         * each `LISTENUP_LOG_LEVEL_<pkg_with_underscores>` sets a prefix override (underscores
         * become dots). Overrides are sorted longest-prefix-first so the most specific wins.
         * Keys with an empty suffix (`LISTENUP_LOG_LEVEL_`) are ignored.
         *
         * The nullable value type is for test ergonomics; production passes `System.getenv()`.
         */
        fun fromEnv(env: Map<String, String?>): LogLevelConfig {
            val default = parseLevel(env[LEVEL_KEY]) ?: Level.INFO
            val overrides =
                env.entries
                    .filter { it.key.startsWith(PREFIX_KEY) && it.key.length > PREFIX_KEY.length }
                    .mapNotNull { (key, value) ->
                        val level = parseLevel(value) ?: return@mapNotNull null
                        key.removePrefix(PREFIX_KEY).replace('_', '.') to level
                    }.sortedByDescending { it.first.length }
            return LogLevelConfig(default, overrides)
        }

        private fun parseLevel(raw: String?): Level? =
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
                runCatching { Level.valueOf(token.uppercase()) }.getOrNull()
            }
    }
}
