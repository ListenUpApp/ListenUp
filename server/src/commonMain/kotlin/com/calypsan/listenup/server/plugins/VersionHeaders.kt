package com.calypsan.listenup.server.plugins

import com.calypsan.listenup.api.VersionHeaders
import com.calypsan.listenup.server.api.ServerIdentity
import com.calypsan.listenup.server.logging.loggerFor
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

private val logger = loggerFor<ClientVersionMetrics>()

/**
 * Stamps `X-Server-Version`/`X-Server-Api` (see [VersionHeaders]) on every response, and records
 * an incoming `X-Client-Version` into [ClientVersionMetrics]. The never-stranded counterpart to
 * the client's outbound `X-Client-*` headers (`ApiClientFactory`) — this half lets the server see
 * what client builds are actually talking to it.
 */
internal fun Application.installVersionHeaders() {
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.headers.append(VersionHeaders.SERVER_VERSION, ServerIdentity.VERSION)
        call.response.headers.append(VersionHeaders.SERVER_API, ServerIdentity.API_VERSION)
        call.request.headers[VersionHeaders.CLIENT_VERSION]?.let { ClientVersionMetrics.record(it) }
    }
}

/**
 * In-memory count of requests seen per distinct `X-Client-Version`. K/N-safe: guarded by
 * [SynchronizedObject] rather than `java.util.concurrent`, which is JVM-only and would break the
 * linuxX64 native build (see [com.calypsan.listenup.server.auth.RegistrationBroadcaster] for the
 * same pattern).
 */
internal object ClientVersionMetrics {
    private val lock = SynchronizedObject()
    private val counts = HashMap<String, Long>()

    /** Increments the count for [version]; logs at INFO the first time a given version is seen. */
    fun record(version: String) {
        val isFirstSighting =
            synchronized(lock) {
                val previous = counts[version]
                counts[version] = (previous ?: 0L) + 1L
                previous == null
            }
        if (isFirstSighting) {
            logger.info { "First request seen from client version $version" }
        }
    }

    /** Snapshot of current counts, keyed by client version. For tests and diagnostics. */
    fun snapshot(): Map<String, Long> = synchronized(lock) { counts.toMap() }
}
