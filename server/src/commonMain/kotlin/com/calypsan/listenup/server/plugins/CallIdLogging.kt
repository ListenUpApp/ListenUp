package com.calypsan.listenup.server.plugins

import io.ktor.server.application.Application

/**
 * Installs request logging that binds the correlation id (from [installCallId]) into the logging
 * context. JVM-only — `ktor-server-call-logging` has no native artifact, so the native actual is a
 * no-op and the native server relies on [installCallId] plus per-route logging.
 */
expect fun Application.installCallLogging()
