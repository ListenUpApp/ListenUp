package com.calypsan.listenup.server.mdns

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

private const val MDNS_GROUP = "224.0.0.251"
private const val MDNS_PORT = 5353

class MulticastMdnsResponderTest :
    FunSpec({
        test("bindMulticastSocket pins the egress interface so sends leave via the joined NIC") {
            // Deterministic guard (works on any host, incl. single-NIC CI): without setNetworkInterface,
            // a multi-homed host sends every announcement out the OS default route instead of `nif`,
            // and no LAN client on the other interfaces ever discovers us.
            val nif =
                firstMulticastIpv4Interface()
                    ?: return@test // no multicast-capable interface here — nothing to assert
            val responder =
                MulticastMdnsResponder(
                    instanceName = "kotest-host",
                    port = 8080,
                    txtProvider = { linkedMapOf("id" to "x") },
                    scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                )

            val socket = responder.bindMulticastSocket(nif)
            try {
                socket.networkInterface.name shouldBe nif.name
            } finally {
                runCatching { socket.leaveGroup(InetSocketAddress(InetAddress.getByName(MDNS_GROUP), MDNS_PORT), nif) }
                socket.close()
            }
        }

        test("start announces an mDNS packet containing our TXT id and service type") {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val responder =
                MulticastMdnsResponder(
                    instanceName = "kotest-host",
                    port = 8080,
                    txtProvider = {
                        linkedMapOf("id" to "test-id-123", "name" to "ListenUp", "version" to "0.0.1", "api" to "v1")
                    },
                    scope = scope,
                )

            val nif = firstMulticastIpv4Interface()
            if (nif == null) {
                // No multicast-capable interface in this environment — cannot exercise real I/O.
                // Unit-level DnsCodec tests + the manual on-LAN DoD check cover encoding; skip here.
                return@test
            }
            val listener =
                MulticastSocket(MDNS_PORT).apply {
                    reuseAddress = true
                    soTimeout = 4000
                    joinGroup(InetSocketAddress(InetAddress.getByName(MDNS_GROUP), MDNS_PORT), nif)
                }

            try {
                responder.start()
                val received =
                    withContext(Dispatchers.IO) {
                        withTimeout(5000) {
                            var hit: String? = null
                            repeat(20) {
                                val buf = ByteArray(2048)
                                val pkt = DatagramPacket(buf, buf.size)
                                runCatching { listener.receive(pkt) }.getOrNull() ?: return@repeat
                                val text = String(pkt.data, 0, pkt.length, Charsets.US_ASCII)
                                if ("id=test-id-123" in text && "_listenup" in text) {
                                    hit = text
                                    return@withTimeout hit
                                }
                            }
                            hit
                        }
                    }
                (received != null) shouldBe true
            } finally {
                responder.stop()
                runCatching { listener.leaveGroup(InetSocketAddress(InetAddress.getByName(MDNS_GROUP), MDNS_PORT), nif) }
                listener.close()
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }

        test("refresh re-reads the txt provider so a renamed server advertises the new name") {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            var serverName = "Old Name"
            val responder =
                MulticastMdnsResponder(
                    instanceName = "kotest-host",
                    port = 8080,
                    txtProvider = { linkedMapOf("id" to "x", "name" to serverName) },
                    scope = scope,
                )
            try {
                responder.start()
                responder.advertisedService().txt["name"] shouldBe "Old Name"

                serverName = "New Name"
                responder.refresh()

                responder.advertisedService().txt["name"] shouldBe "New Name"
            } finally {
                responder.stop()
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }

        test("isVirtualInterfaceName flags docker/VPN/VM NICs but keeps real LAN ifaces and bridges") {
            // The user's failing host had docker0 (172.17.0.1) + tailscale0 (100.x) advertised
            // alongside the real LAN — both unroutable from a LAN client. These must be excluded.
            val virtual =
                listOf(
                    "docker0",
                    "veth1a2b",
                    "virbr0",
                    "vboxnet0",
                    "vmnet8",
                    "tailscale0",
                    "zt5u4i",
                    "tun0",
                    "tap0",
                    "utun3",
                    "wg0",
                )
            virtual.filterNot(::isVirtualInterfaceName) shouldBe emptyList()

            // Real LAN interfaces — including a Linux host whose LAN is itself a bridge — stay in.
            val real = listOf("eth0", "enp8s0", "wlan0", "en0", "br0", "br-lan", "bond0")
            real.filter(::isVirtualInterfaceName) shouldBe emptyList()
        }

        test("start resolves the host label from hostLabelProvider so the A record dodges the OS hostname") {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val responder =
                MulticastMdnsResponder(
                    instanceName = "omarchy",
                    port = 8080,
                    txtProvider = { linkedMapOf("id" to "abc") },
                    scope = scope,
                    hostLabelProvider = { "listenup-abc" },
                )
            try {
                responder.start()
                responder.advertisedService().hostLabel shouldBe "listenup-abc"
                responder.advertisedService().instanceName shouldBe "omarchy"
            } finally {
                responder.stop()
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }

        test("refresh before start is a no-op — a never-started advertiser ignores rename nudges") {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val responder =
                MulticastMdnsResponder(
                    instanceName = "kotest-host",
                    port = 8080,
                    txtProvider = { linkedMapOf("id" to "x", "name" to "Should Not Be Read") },
                    scope = scope,
                )
            try {
                responder.refresh()
                responder.advertisedService().txt.isEmpty() shouldBe true
            } finally {
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            }
        }
    })

// Mirrors the responder's own "advertisable interface" predicate (up + multicast + IPv4, minus
// loopback / point-to-point / virtual NICs) so the listener joins the group on an interface the
// responder actually announces on — otherwise a host with docker0/tailscale0 first in enumeration
// would have the test join a NIC the responder (correctly) skips, and receive nothing.
private fun firstMulticastIpv4Interface(): NetworkInterface? =
    NetworkInterface.getNetworkInterfaces().toList().firstOrNull { nif ->
        runCatching {
            nif.isUp && nif.supportsMulticast() && !nif.isLoopback && !nif.isPointToPoint &&
                !isVirtualInterfaceName(nif.name)
        }.getOrDefault(false) &&
            nif.inetAddresses.toList().any { it.address.size == 4 }
    }
