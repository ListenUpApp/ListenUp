package com.calypsan.listenup.server.mdns

/** One bound, group-joined multicast socket for a single IPv4 interface. */
internal interface MdnsSocket {
    val interfaceName: String

    /** The interface's 4-byte IPv4 address — the A record we advertise. */
    val ipv4: ByteArray

    /** Sends [payload] to 224.0.0.251:5353 via this socket's pinned egress interface. Best-effort. */
    fun send(payload: ByteArray)

    /** Blocks until a datagram arrives, returning its bytes; null on close/error (the cancel signal). */
    fun receive(): ByteArray?

    /** Best-effort IP_DROP_MEMBERSHIP + close the fd (also unblocks a blocked [receive]). */
    fun leaveAndClose()
}

/**
 * Binds + joins one [MdnsSocket] per multicast-capable, non-virtual IPv4 LAN interface
 * (224.0.0.251:5353, SO_REUSEADDR+SO_REUSEPORT, egress pinned). Empty when no usable interface exists
 * (advertise-disabled — the manual-URL fallback covers it). Filters via [isVirtualInterfaceName] + the
 * up/multicast/!loopback/!pointToPoint/has-IPv4 checks.
 */
internal expect fun openMdnsSockets(): List<MdnsSocket>
