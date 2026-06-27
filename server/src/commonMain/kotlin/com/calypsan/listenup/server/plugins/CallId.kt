package com.calypsan.listenup.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import kotlin.uuid.Uuid

internal const val REQUEST_ID_HEADER = "X-Request-Id"
internal const val MDC_KEY = "correlationId"

/**
 * Generates a per-request correlation id and surfaces it on the response as `X-Request-Id`.
 *
 * Native-capable: the [CallId] plugin and UUID generation have no JVM-only dependency, so this
 * is the half of request-correlation that the Kotlin/Native server can install. The JVM server
 * additionally binds the id into the SLF4J MDC (under [MDC_KEY]) and logs each request via
 * [installCallLogging] — that half stays JVM-only because `ktor-server-call-logging` has no
 * native artifact.
 *
 * The id flows into [io.ktor.server.plugins.callid.callId], which `AppErrorStatusPages` reads to
 * stamp the correlation id onto wire errors.
 */
fun Application.installCallId() {
    install(CallId) {
        generate { Uuid.random().toString() }
        verify { it.isNotBlank() }
        replyToHeader(headerName = REQUEST_ID_HEADER)
    }
}
