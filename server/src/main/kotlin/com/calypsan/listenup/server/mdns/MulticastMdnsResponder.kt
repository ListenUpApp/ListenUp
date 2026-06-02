package com.calypsan.listenup.server.mdns

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

private val log = KotlinLogging.logger("mdns.MulticastMdnsResponder")

/**
 * Pure-Kotlin advertise-only mDNS responder over [MulticastSocket]. Binds one socket per suitable
 * IPv4 interface (non-loopback, up, multicast-capable) joined to 224.0.0.251:5353 with address reuse
 * so it coexists with a host avahi/mDNSResponder. On [start] it sends the announcement x3 (~1s apart)
 * and serves PTR queries; on [stop] it sends a goodbye (TTL 0) and closes everything.
 *
 * All failures are swallowed (logged) — advertisement is non-critical; the manual-URL fallback covers
 * a host without working multicast.
 */
class MulticastMdnsResponder(
    private val service: MdnsServiceInfo,
    private val scope: CoroutineScope,
) : MdnsAdvertiser {
    private val group: InetAddress = InetAddress.getByName(GROUP)
    private val sockets = mutableListOf<Bound>()
    private val jobs = mutableListOf<Job>()

    private data class Bound(
        val socket: MulticastSocket,
        val nif: NetworkInterface,
        val ipv4: ByteArray,
    )

    override suspend fun start() {
        withContext(Dispatchers.IO) {
            runCatching {
                for (nif in multicastIpv4Interfaces()) {
                    val ipv4 =
                        nif.inetAddresses
                            .toList()
                            .filterIsInstance<Inet4Address>()
                            .firstOrNull() ?: continue
                    val socket =
                        MulticastSocket(PORT).apply {
                            reuseAddress = true
                            joinGroup(InetSocketAddress(group, PORT), nif)
                        }
                    sockets += Bound(socket, nif, ipv4.address)
                }
                if (sockets.isEmpty()) {
                    log.warn {
                        "mDNS: no multicast-capable IPv4 interface — advertisement disabled (manual URL still works)"
                    }
                    return@runCatching
                }
                for (bound in sockets) {
                    jobs += scope.launch(Dispatchers.IO) { receiveLoop(bound) }
                    jobs += scope.launch(Dispatchers.IO) { announce(bound) }
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

    private suspend fun announce(bound: Bound) {
        val packet = DnsCodec.encodeResponse(service, bound.ipv4, ttlSeconds = TTL_SECONDS)
        repeat(ANNOUNCE_COUNT) {
            send(bound, packet)
            delay(ANNOUNCE_INTERVAL_MS)
        }
    }

    private suspend fun receiveLoop(bound: Bound) {
        val buf = ByteArray(BUFFER_SIZE)
        while (scope.isActive) {
            val pkt = DatagramPacket(buf, buf.size)
            val ok = runCatching { bound.socket.receive(pkt) }.isSuccess
            if (!ok) {
                if (!scope.isActive) break
                continue
            }
            val query = pkt.data.copyOf(pkt.length)
            if (DnsCodec.isQueryForUs(query)) {
                send(bound, DnsCodec.encodeResponse(service, bound.ipv4, ttlSeconds = TTL_SECONDS))
            }
        }
    }

    private fun send(
        bound: Bound,
        payload: ByteArray,
    ) {
        runCatching {
            bound.socket.send(DatagramPacket(payload, payload.size, group, PORT))
        }.onFailure { e ->
            if (e is CancellationException) throw e
            log.debug(e) { "mDNS: send failed on ${bound.nif.name}" }
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            runCatching {
                val goodbye = sockets.map { it to DnsCodec.encodeResponse(service, it.ipv4, ttlSeconds = 0) }
                jobs.forEach { it.cancel() }
                jobs.clear()
                goodbye.forEach { (bound, payload) -> send(bound, payload) }
                sockets.forEach {
                    runCatching { it.socket.leaveGroup(InetSocketAddress(group, PORT), it.nif) }
                    it.socket.close()
                }
                sockets.clear()
                log.info { "mDNS advertisement stopped" }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                log.warn(e) { "mDNS: error during stop" }
            }
        }
    }

    private fun multicastIpv4Interfaces(): List<NetworkInterface> =
        NetworkInterface
            .getNetworkInterfaces()
            .toList()
            .filter { nif ->
                runCatching { nif.isUp && nif.supportsMulticast() && !nif.isLoopback }.getOrDefault(false) &&
                    nif.inetAddresses.toList().any { it is Inet4Address }
            }.ifEmpty {
                // Fall back to loopback so a dev box / CI with only `lo` can still bind (tests rely on this).
                NetworkInterface.getNetworkInterfaces().toList().filter {
                    runCatching { it.isLoopback && it.supportsMulticast() }.getOrDefault(false)
                }
            }

    private companion object {
        const val GROUP = "224.0.0.251"
        const val PORT = 5353
        const val TTL_SECONDS = 120
        const val ANNOUNCE_COUNT = 3
        const val ANNOUNCE_INTERVAL_MS = 1000L
        const val BUFFER_SIZE = 2048
    }
}
