package com.calypsan.listenup.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import org.slf4j.event.Level

/**
 * JVM request logging: binds the correlation id (from [installCallId]) into the SLF4J MDC under
 * [MDC_KEY] and logs each request at INFO. Install order is [installCallId] then [installCallLogging]
 * so the correlation id exists before the MDC binding reads it.
 */
actual fun Application.installCallLogging() {
    install(CallLogging) {
        level = Level.INFO
        callIdMdc(MDC_KEY)
    }
}
