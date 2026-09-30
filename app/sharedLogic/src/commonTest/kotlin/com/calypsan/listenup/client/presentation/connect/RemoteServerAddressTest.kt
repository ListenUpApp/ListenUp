package com.calypsan.listenup.client.presentation.connect

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [isClearlyRemoteServerAddress] decides whether the manual-entry screen can set aside its
 * local-network card: only for an address that cannot be on the user's network. Anything that
 * might be local — including an address still being typed — keeps the card, because on Android 17
 * a local address will fail without the permission.
 */
class RemoteServerAddressTest :
    FunSpec({
        listOf(
            "listenup.example.com",
            "https://yourname.listenup.app",
            "https://yourname.listenup.app:443/",
            "203.0.113.7:8080",
            "http://8.8.8.8",
            "[2001:db8::1]:8080",
            // Tailscale's CGNAT range travels over a VPN the permission never covers.
            "100.64.0.1:8080",
        ).forEach { typed ->
            test("'$typed' is clearly remote") { isClearlyRemoteServerAddress(typed) shouldBe true }
        }

        listOf(
            "",
            "   ",
            "http://",
            "192.168",
            "192.168.1",
            "192.168.1.5:8080",
            "10.0.0.2",
            "[fe80::1]:8080",
            "nas.local",
            "nas.local.",
            // A bare or private-suffix name may resolve to the LAN; no DNS is done for this decision.
            "nas",
            "nas.lan",
            "router.home.arpa",
            "server.internal",
            "http://[20",
        ).forEach { typed ->
            test("'$typed' might be local") { isClearlyRemoteServerAddress(typed) shouldBe false }
        }
    })
