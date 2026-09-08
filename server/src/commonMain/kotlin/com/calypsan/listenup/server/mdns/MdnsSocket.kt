package com.calypsan.listenup.server.mdns

/** One bound, group-joined multicast socket for a single IPv4 interface. */
internal interface MdnsSocket {
    val interfaceName: String

    /** The interface's 4-byte IPv4 address — the A record we advertise. */
    val ipv4: ByteArray

    /** Sends [payload] to 224.0.0.251:5353 via this socket's pinned egress interface. Best-effort. */
    fun send(payload: ByteArray)

    /**
     * Blocks until a datagram arrives and returns its bytes.
     *
     * Returns an **empty array** for a zero-length datagram or a retryable read (`EINTR`/`EAGAIN`)
     * — the caller skips it and reads again — and `null` **only** when the socket is closed or the
     * error is unrecoverable (the cancel signal). Both actuals are held to this: a `null` ends the
     * receive loop for the life of the process.
     */
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
