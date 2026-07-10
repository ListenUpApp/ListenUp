package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.VersionHeaders
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.observer.ResponseObserver
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Installs a response hook that captures the peer server's version + API contract version off
 * every response's [VersionHeaders.SERVER_VERSION]/[VersionHeaders.SERVER_API] headers (the
 * server's Task 10 reply) and forwards a CHANGED pair to [onPeerVersion].
 *
 * Debounced against the last-seen pair: a response arrives on every request, and [onPeerVersion]
 * is expected to persist to secure storage (see `networkModule`'s wiring to
 * `LocalPreferences::setPeerServerVersion`) — firing on every response would hammer storage with
 * a redundant write for an unchanged server. The last-seen value is guarded by a [Mutex] rather
 * than a bare `var` so concurrent in-flight requests can't race the compare-and-set.
 *
 * Uses [ResponseObserver] rather than the client's `HttpSend` interceptor: it is the purpose-built
 * Ktor plugin for cheap per-response side effects and never blocks or reshapes the response the
 * caller ultimately receives.
 */
internal fun HttpClientConfig<*>.installPeerVersionCapture(
    onPeerVersion: suspend (version: String, api: String) -> Unit,
) {
    val lastSeenMutex = Mutex()
    var lastSeen: Pair<String, String>? = null

    ResponseObserver { response: HttpResponse ->
        val version = response.headers[VersionHeaders.SERVER_VERSION] ?: return@ResponseObserver
        val api = response.headers[VersionHeaders.SERVER_API] ?: return@ResponseObserver
        val current = version to api

        val changed =
            lastSeenMutex.withLock {
                if (lastSeen == current) {
                    false
                } else {
                    lastSeen = current
                    true
                }
            }
        if (changed) onPeerVersion(version, api)
    }
}
