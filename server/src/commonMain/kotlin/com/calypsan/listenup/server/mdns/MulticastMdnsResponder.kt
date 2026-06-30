package com.calypsan.listenup.server.mdns

import com.calypsan.listenup.core.IODispatcher
import com.calypsan.listenup.server.logging.loggerFor
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = loggerFor<MulticastMdnsResponder>()

/**
 * Advertise-only mDNS responder over the platform [MdnsSocketLayer] seam (java.net on JVM, POSIX on
 * native). Binds one socket per suitable IPv4 interface (224.0.0.251:5353); on [start] announces x3
 * (~1s apart) and serves PTR queries; on [refresh] rebuilds the TXT from [txtProvider] and re-announces;
 * on [stop] sends a goodbye (TTL 0) and closes everything.
 *
 * [txtProvider] is read on every [start]/[refresh] so the advertised TXT always reflects the live server
 * identity (an admin rename propagates without a restart). All failures are swallowed (logged) —
 * advertisement is non-critical; manual URL entry is the fallback.
 */
class MulticastMdnsResponder(
    private val instanceName: String,
    private val port: Int,
    private val txtProvider: suspend () -> Map<String, String>,
    private val scope: CoroutineScope,
    private val hostLabelProvider: suspend () -> String = { instanceName },
) : MdnsAdvertiser {
    private val sockets = mutableListOf<MdnsSocket>()
    private val jobs = mutableListOf<Job>()

    @Volatile
    private var service: MdnsServiceInfo = MdnsServiceInfo(instanceName, port, emptyMap())

    @Volatile
    private var started = false

    /** The currently-advertised record — test seam for asserting [refresh] picked up the new TXT. */
    internal fun advertisedService(): MdnsServiceInfo = service

    override suspend fun start() {
        service = MdnsServiceInfo(instanceName, port, txtProvider(), hostLabel = hostLabelProvider())
        started = true
        withContext(IODispatcher) {
            runCatching {
                if (sockets.isNotEmpty()) return@runCatching
                sockets += openMdnsSockets()
                if (sockets.isEmpty()) {
                    log.warn {
                        "mDNS: no multicast-capable IPv4 interface — advertisement disabled (manual URL still works)"
                    }
                    return@runCatching
                }
                for (socket in sockets) {
                    jobs += scope.launch(IODispatcher) { receiveLoop(socket) }
                    jobs += scope.launch(IODispatcher) { announce(socket) }
                }
                log.info {
                    "mDNS advertisement started: ${MdnsServiceInfo.SERVICE_TYPE} port=${service.port} on ${sockets.size} interface(s)"
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                log.warn(e) { "mDNS: failed to start advertisement — continuing without it" }
            }
        }
    }

    override suspend fun refresh() {
        if (!started) return
        service = service.copy(txt = txtProvider())
        withContext(IODispatcher) {
            runCatching {
                val bounds = sockets.toList()
                if (bounds.isEmpty()) return@runCatching
                bounds.forEach { socket -> jobs += scope.launch(IODispatcher) { announce(socket) } }
                log.info { "mDNS advertisement refreshed: re-announced on ${bounds.size} interface(s)" }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                log.warn(e) { "mDNS: failed to refresh advertisement — keeping the previous announcement" }
            }
        }
    }

    private suspend fun announce(socket: MdnsSocket) {
        val packet = DnsCodec.encodeResponse(service, socket.ipv4, ttlSeconds = TTL_SECONDS)
        repeat(ANNOUNCE_COUNT) {
            socket.send(packet)
            delay(ANNOUNCE_INTERVAL_MS)
        }
    }

    private suspend fun receiveLoop(socket: MdnsSocket) {
        while (currentCoroutineContext().isActive) {
            val query = socket.receive() ?: break
            if (DnsCodec.isQueryForUs(query)) {
                socket.send(DnsCodec.encodeResponse(service, socket.ipv4, ttlSeconds = TTL_SECONDS))
            }
        }
    }

    override suspend fun stop() {
        withContext(IODispatcher) {
            runCatching {
                val goodbye = sockets.map { it to DnsCodec.encodeResponse(service, it.ipv4, ttlSeconds = 0) }
                jobs.forEach { it.cancel() }
                jobs.clear()
                goodbye.forEach { (socket, payload) -> socket.send(payload) }
                sockets.forEach { it.leaveAndClose() }
                sockets.clear()
                log.info { "mDNS advertisement stopped" }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                log.warn(e) { "mDNS: error during stop" }
            }
        }
    }

    private companion object {
        const val TTL_SECONDS = 120
        const val ANNOUNCE_COUNT = 3
        const val ANNOUNCE_INTERVAL_MS = 1000L
    }
}
