package com.calypsan.listenup.server

import io.ktor.server.cio.EngineMain

/**
 * Process entrypoint.
 *
 * Log format is selected by [com.calypsan.listenup.server.logging.ListenUpLogProvider]
 * at SLF4J initialization time via the `LISTENUP_LOG_FORMAT` environment variable
 * (`json` → JSON one-per-line, anything else → plain text). No system properties
 * or logback XML files are required.
 */
fun main(args: Array<String>) {
    EngineMain.main(args)
}
