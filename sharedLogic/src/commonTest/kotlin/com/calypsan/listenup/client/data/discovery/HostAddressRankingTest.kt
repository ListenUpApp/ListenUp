package com.calypsan.listenup.client.data.discovery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [rankHostAddresses] — the best-first ordering that keeps an unroutable advertised
 * address (docker bridge, VPN CGNAT, link-local) from being tried before a reachable LAN address.
 */
class HostAddressRankingTest :
    FunSpec({
        test("routable IPv4 leads; IPv6, then CGNAT, then link-local/loopback trail") {
            rankHostAddresses(
                listOf("169.254.1.1", "100.68.42.108", "fd53::1", "192.168.86.39", "127.0.0.1"),
            ) shouldBe listOf("192.168.86.39", "fd53::1", "100.68.42.108", "169.254.1.1", "127.0.0.1")
        }

        test("preserves the announced order within a tier (stable sort)") {
            // docker's 172.17.0.1 is RFC1918 like 192.168/10 — indistinguishable by address, so it
            // stays in announced order; the connect path still tries every address.
            rankHostAddresses(listOf("192.168.86.39", "10.0.0.5", "172.17.0.1")) shouldBe
                listOf("192.168.86.39", "10.0.0.5", "172.17.0.1")
        }

        test("dedupes repeated addresses") {
            rankHostAddresses(listOf("192.168.86.39", "192.168.86.39")) shouldBe listOf("192.168.86.39")
        }

        test("empty in, empty out") {
            rankHostAddresses(emptyList()) shouldBe emptyList()
        }
    })
