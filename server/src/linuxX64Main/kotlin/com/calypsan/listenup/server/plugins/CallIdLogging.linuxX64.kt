package com.calypsan.listenup.server.plugins

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlin.time.TimeSource

private val logger = KotlinLogging.logger {}

/**
 * Native request logging: `ktor-server-call-logging` has no Kotlin/Native artifact, so this actual
 * reproduces its essentials by intercepting the pipeline — timing each call, then logging method,
 * path, status, duration, and the [installCallId] correlation id (inlined because native has no SLF4J
 * MDC). Mirrors the JVM actual's INFO access log.
 */
actual fun Application.installCallLogging() {
    intercept(ApplicationCallPipeline.Monitoring) {
        val started = TimeSource.Monotonic.markNow()
        try {
            proceed()
        } finally {
            val durationMs = started.elapsedNow().inWholeMilliseconds
            val status =
                call.response
                    .status()
                    ?.value
                    ?.toString() ?: "-"
            val correlationId = call.callId ?: "-"
            logger.info {
                "${call.request.httpMethod.value} ${call.request.path()} -> $status (${durationMs}ms) [$correlationId]"
            }
        }
    }
}
