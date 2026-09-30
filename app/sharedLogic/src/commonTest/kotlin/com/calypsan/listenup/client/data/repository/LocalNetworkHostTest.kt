package com.calypsan.listenup.client.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [isLocalNetworkHost] decides whether Android's local-network permission could be the thing that
 * stopped a connection. A false positive tells a user with a perfectly good VPN or public server
 * to go and change a permission; a false negative is the original bug — a LAN server blamed for
 * being down.
 */
class LocalNetworkHostTest :
    FunSpec({
        listOf(
            "10.0.0.5",
            "10.255.255.255",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.5",
            "192.168.86.250",
            "169.254.10.20",
            "fe80::1",
            "FE80::1c2d:3e4f",
            "febf::1",
            "fe80::1%en0",
            "[fe80::1]",
            "fc00::1",
            "fd12:3456:789a::1",
            "nas.local",
            "NAS.LOCAL",
            "nas.local.",
            "::ffff:192.168.1.5",
        ).forEach { host ->
            test("'$host' is on the local network") { isLocalNetworkHost(host) shouldBe true }
        }

        listOf(
            // Loopback is the device itself, not the network.
            "127.0.0.1",
            "localhost",
            "::1",
            // Public addresses and names.
            "8.8.8.8",
            "172.15.255.255",
            "172.32.0.1",
            "192.169.0.1",
            "11.0.0.1",
            "2001:4860:4860::8888",
            "fec0::1",
            "listenup.example.com",
            "yourname.listenup.app",
            // CGNAT: Tailscale hands these out over a VPN interface the gate never sees.
            "100.64.0.1",
            "100.100.100.100",
            "100.127.255.254",
            // Not an address at all.
            "",
            "192.168.1",
            "192.168.1.256",
            "nas.localdomain",
        ).forEach { host ->
            test("'$host' is not on the local network") { isLocalNetworkHost(host) shouldBe false }
        }
    })
