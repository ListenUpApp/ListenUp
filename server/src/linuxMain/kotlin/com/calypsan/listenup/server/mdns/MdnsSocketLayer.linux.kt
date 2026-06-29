package com.calypsan.listenup.server.mdns

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.linux.freeifaddrs
import platform.linux.getifaddrs
import platform.linux.ifaddrs
import platform.linux.inet_addr
import platform.posix.AF_INET
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_MULTICAST
import platform.posix.IFF_POINTOPOINT
import platform.posix.IFF_UP
import platform.posix.INADDR_ANY
import platform.posix.IPPROTO_IP
import platform.posix.IP_ADD_MEMBERSHIP
import platform.posix.IP_DROP_MEMBERSHIP
import platform.posix.IP_MULTICAST_IF
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.SO_REUSEPORT
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.htons
import platform.posix.in_addr
import platform.posix.ip_mreq
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket

private val logger = KotlinLogging.logger {}

private const val GROUP = "224.0.0.251"
private const val PORT: UShort = 5353u
private const val RECEIVE_BUFFER_SIZE = 2048

/**
 * Binds + joins one POSIX UDP socket per suitable IPv4 interface (up, multicast-capable, non-loopback,
 * non-point-to-point, non-virtual), enumerated via `getifaddrs`. Each socket binds `INADDR_ANY:5353`
 * with SO_REUSEADDR + SO_REUSEPORT (so it coexists with a host avahi/mDNSResponder on the same port),
 * pins egress to the interface address via `IP_MULTICAST_IF`, and joins 224.0.0.251 via
 * `IP_ADD_MEMBERSHIP`. Best-effort: an interface that fails any setup step is skipped.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun openMdnsSockets(): List<MdnsSocket> {
    val sockets = mutableListOf<MdnsSocket>()
    memScoped {
        val listHead = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(listHead.ptr) != 0) return emptyList()
        try {
            var cur: CPointer<ifaddrs>? = listHead.value
            while (cur != null) {
                val entry = cur.pointed
                val sa = entry.ifa_addr
                if (sa != null && sa
                        .reinterpret<sockaddr>()
                        .pointed.sa_family
                        .toInt() == AF_INET
                ) {
                    val flags = entry.ifa_flags.toInt()
                    val up = (flags and IFF_UP) != 0
                    val multicast = (flags and IFF_MULTICAST) != 0
                    val loopback = (flags and IFF_LOOPBACK) != 0
                    val pointToPoint = (flags and IFF_POINTOPOINT) != 0
                    val name = entry.ifa_name?.toKString()
                    if (up && multicast && !loopback && !pointToPoint &&
                        name != null && !isVirtualInterfaceName(name)
                    ) {
                        // sin_addr.s_addr is already network byte order — the A record we advertise.
                        val ifAddr =
                            sa
                                .reinterpret<sockaddr_in>()
                                .pointed.sin_addr.s_addr
                        openSocket(name, ifAddr)?.let { sockets += it }
                    }
                }
                cur = entry.ifa_next?.reinterpret()
            }
        } finally {
            freeifaddrs(listHead.value)
        }
    }
    return sockets
}

/**
 * Open one bound + joined socket for the interface at [ifAddrNetworkOrder]. Returns null (closing the
 * fd) if any setup syscall fails, so a single unusable interface can't abort the whole enumeration.
 */
@OptIn(ExperimentalForeignApi::class)
private fun openSocket(
    interfaceName: String,
    ifAddrNetworkOrder: UInt,
): MdnsSocket? {
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    if (fd < 0) return null
    val ok =
        memScoped {
            val reuse = alloc<IntVar>()
            reuse.value = 1
            val reuseLen = sizeOf<IntVar>().convert<UInt>()
            if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, reuse.ptr, reuseLen) != 0) return@memScoped false
            if (setsockopt(fd, SOL_SOCKET, SO_REUSEPORT, reuse.ptr, reuseLen) != 0) return@memScoped false

            val bindAddr = alloc<sockaddr_in>()
            bindAddr.sin_family = AF_INET.convert()
            bindAddr.sin_port = htons(PORT)
            bindAddr.sin_addr.s_addr = INADDR_ANY.convert()
            if (bind(fd, bindAddr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) return@memScoped false

            val egress = alloc<in_addr>()
            egress.s_addr = ifAddrNetworkOrder
            if (setsockopt(fd, IPPROTO_IP, IP_MULTICAST_IF, egress.ptr, sizeOf<in_addr>().convert()) != 0) {
                return@memScoped false
            }

            val mreq = alloc<ip_mreq>()
            mreq.imr_multiaddr.s_addr = inet_addr(GROUP)
            mreq.imr_interface.s_addr = ifAddrNetworkOrder
            setsockopt(fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, mreq.ptr, sizeOf<ip_mreq>().convert()) == 0
        }
    if (!ok) {
        close(fd)
        return null
    }
    return NativeMdnsSocket(fd, interfaceName, ifAddrNetworkOrder)
}

/**
 * One POSIX multicast socket. [send] datagrams to 224.0.0.251:5353; [receive] blocks in `recvfrom`
 * until a datagram arrives or the fd is closed ([leaveAndClose] from another thread → EBADF unblocks
 * it, returning null — the receive-loop cancellation path, mirroring JVM `socket.receive` on close).
 */
@OptIn(ExperimentalForeignApi::class)
private class NativeMdnsSocket(
    private val fd: Int,
    override val interfaceName: String,
    private val ifAddrNetworkOrder: UInt,
) : MdnsSocket {
    // sin_addr.s_addr is network byte order: copy the 4 bytes directly as the advertised A record.
    override val ipv4: ByteArray =
        byteArrayOf(
            (ifAddrNetworkOrder and 0xFFu).toByte(),
            ((ifAddrNetworkOrder shr 8) and 0xFFu).toByte(),
            ((ifAddrNetworkOrder shr 16) and 0xFFu).toByte(),
            ((ifAddrNetworkOrder shr 24) and 0xFFu).toByte(),
        )

    override fun send(payload: ByteArray) {
        memScoped {
            val dest = alloc<sockaddr_in>()
            dest.sin_family = AF_INET.convert()
            dest.sin_port = htons(PORT)
            dest.sin_addr.s_addr = inet_addr(GROUP)
            payload.usePinned { pinned ->
                val sent =
                    sendto(
                        fd,
                        pinned.addressOf(0),
                        payload.size.convert(),
                        0,
                        dest.ptr.reinterpret(),
                        sizeOf<sockaddr_in>().convert(),
                    )
                if (sent < 0) {
                    logger.warn { "mDNS sendto failed on $interfaceName (errno=$errno) — advertisement not sent" }
                }
            }
        }
    }

    override fun receive(): ByteArray? {
        val buffer = ByteArray(RECEIVE_BUFFER_SIZE)
        val received =
            buffer.usePinned { pinned ->
                recvfrom(fd, pinned.addressOf(0), RECEIVE_BUFFER_SIZE.convert(), 0, null, null)
            }
        return if (received <= 0) null else buffer.copyOf(received.toInt())
    }

    override fun leaveAndClose() {
        memScoped {
            val mreq = alloc<ip_mreq>()
            mreq.imr_multiaddr.s_addr = inet_addr(GROUP)
            mreq.imr_interface.s_addr = ifAddrNetworkOrder
            setsockopt(fd, IPPROTO_IP, IP_DROP_MEMBERSHIP, mreq.ptr, sizeOf<ip_mreq>().convert())
            Unit
        }
        close(fd)
    }
}
