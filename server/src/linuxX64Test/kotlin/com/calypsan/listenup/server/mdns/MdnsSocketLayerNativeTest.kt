package com.calypsan.listenup.server.mdns

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Native construction/teardown proof for the mDNS POSIX socket layer ([openMdnsSockets] → getifaddrs /
 * socket / setsockopt / IP_ADD_MEMBERSHIP cinterop). Enumerates interfaces, opens one multicast socket
 * per usable IPv4 interface, validates each socket's shape, then leaves the group and closes the fd.
 *
 * Scope boundary: full multicast send/receive is NOT exercised — a CI runner's network namespace may have
 * no multicast-capable interface (the returned list is then legitimately empty) and outbound multicast is
 * unreliable in sandboxes. This test proves the enumeration + bind + join + teardown syscall path runs
 * cleanly and releases its file descriptors; it does not assert a datagram round-trip.
 */
class MdnsSocketLayerNativeTest :
    FunSpec({
        test("opens and closes multicast sockets without crashing") {
            // May be empty on a runner with no usable interface — a valid outcome (advertise-disabled).
            val sockets = openMdnsSockets()
            try {
                for (socket in sockets) {
                    socket.ipv4.size shouldBe IPV4_BYTES
                    socket.interfaceName.isNotBlank() shouldBe true
                }
            } finally {
                sockets.forEach { it.leaveAndClose() }
            }
        }
    })

private const val IPV4_BYTES = 4
