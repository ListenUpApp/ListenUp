package com.calypsan.listenup.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import org.slf4j.event.Level
import java.util.UUID

private const val REQUEST_ID_HEADER = "X-Request-Id"
private const val MDC_KEY = "correlationId"

/**
 * Generates a per-request correlation id, surfaces it on the response as
 * `X-Request-Id`, and binds it into the SLF4J MDC under `correlationId` for
 * the duration of the request scope.
 *
 * The MDC value flows into the log pattern and into [io.ktor.server.plugins.callid.callId]
 * (used by `AppErrorStatusPages` to stamp the id onto wire errors).
 */
fun Application.installCallIdAndLogging() {
    install(CallId) {
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
        replyToHeader(headerName = REQUEST_ID_HEADER)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc(MDC_KEY)
    }
}
