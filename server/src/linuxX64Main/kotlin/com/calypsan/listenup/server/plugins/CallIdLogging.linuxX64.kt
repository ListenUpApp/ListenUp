package com.calypsan.listenup.server.plugins

import io.ktor.server.application.Application

/**
 * No-op: `ktor-server-call-logging` has no Kotlin/Native artifact. The native server keeps
 * [installCallId] (correlation ids still flow) but omits per-request access logging.
 */
actual fun Application.installCallLogging() = Unit
