package com.calypsan.listenup.server.mdns

import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.net.StandardSocketOptions

private const val GROUP = "224.0.0.251"
private const val PORT = 5353
private const val RECEIVE_BUFFER_SIZE = 2048

/**
 * Binds one [MulticastSocket] per suitable IPv4 interface (up, multicast-capable, non-loopback,
 * non-point-to-point, non-virtual, has an IPv4 address) and joins 224.0.0.251:5353 on each. The egress
 * interface is pinned via [MulticastSocket.setNetworkInterface] — load-bearing on multi-homed hosts,
 * where without it every `send` would leave via the OS default route and never reach the intended LAN.
 */
internal actual fun openMdnsSockets(): List<MdnsSocket> =
    multicastIpv4Interfaces().mapNotNull { nif ->
        val ipv4 =
            nif.inetAddresses
                .toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull() ?: return@mapNotNull null
        JvmMdnsSocket(bindMulticastSocket(nif), nif, ipv4.address)
    }

/**
 * The LAN interfaces we announce on. Beyond up + multicast-capable + non-loopback + has-IPv4, we
 * exclude virtual / non-LAN interfaces — docker/VM bridges and VPN/tunnel links — because a client on
 * the real LAN can't route to them and advertising one would put an unreachable A record into
 * discovery. Point-to-point links are dropped by flag; the rest by name ([isVirtualInterfaceName]).
 */
private fun multicastIpv4Interfaces(): List<NetworkInterface> =
    NetworkInterface
        .getNetworkInterfaces()
        .toList()
        .filter { nif ->
            runCatching {
                nif.isUp && nif.supportsMulticast() && !nif.isLoopback && !nif.isPointToPoint &&
                    !isVirtualInterfaceName(nif.name)
            }.getOrDefault(false) &&
                nif.inetAddresses.toList().any { it is Inet4Address }
        }

private fun bindMulticastSocket(nif: NetworkInterface): MulticastSocket =
    MulticastSocket(PORT).apply {
        reuseAddress = true
        runCatching { setOption(StandardSocketOptions.SO_REUSEPORT, true) }
        networkInterface = nif
        joinGroup(InetSocketAddress(InetAddress.getByName(GROUP), PORT), nif)
    }

private class JvmMdnsSocket(
    private val socket: MulticastSocket,
    private val nif: NetworkInterface,
    override val ipv4: ByteArray,
) : MdnsSocket {
    private val group: InetAddress = InetAddress.getByName(GROUP)

    override val interfaceName: String get() = nif.name

    override fun send(payload: ByteArray) {
        runCatching { socket.send(DatagramPacket(payload, payload.size, group, PORT)) }
    }

    override fun receive(): ByteArray? {
        val packet = DatagramPacket(ByteArray(RECEIVE_BUFFER_SIZE), RECEIVE_BUFFER_SIZE)
        return try {
            socket.receive(packet)
            packet.data.copyOf(packet.length)
        } catch (_: SocketException) {
            null
        }
    }

    override fun leaveAndClose() {
        runCatching { socket.leaveGroup(InetSocketAddress(group, PORT), nif) }
        socket.close()
    }
}
