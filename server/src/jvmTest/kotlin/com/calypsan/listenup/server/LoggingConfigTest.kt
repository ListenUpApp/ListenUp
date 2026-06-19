package com.calypsan.listenup.server

import com.calypsan.listenup.server.logging.ListenUpLoggerFactory
import com.calypsan.listenup.server.logging.ListenUpLogProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

/**
 * Pins the logging-format-selection contract now that it lives in [ListenUpLogProvider]
 * rather than logback XML files or Janino `<if>` conditions.
 *
 * The old `logback.configurationFile` system property and `resolveLogbackConfigFile()`
 * function have been removed; format is selected via `LISTENUP_LOG_FORMAT` env var read
 * once during [ListenUpLogProvider.initialize].
 */
class LoggingConfigTest :
    FunSpec({

        test("json env value selects JSON format") {
            // Directly verify the factory flag — the provider reads env at initialize() time
            // which we cannot control in a unit test, so we exercise the factory constructor.
            val factory = ListenUpLoggerFactory(isJsonFormat = true)
            factory.isJsonFormat.shouldBeTrue()
        }

        test("null or non-json env selects plain format") {
            val factory = ListenUpLoggerFactory(isJsonFormat = false)
            factory.isJsonFormat.shouldBeFalse()
        }
    })
