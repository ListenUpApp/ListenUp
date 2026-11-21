package com.calypsan.listenup.client.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Regression pins for [NetworkStatusPolicy].
 *
 * ListenUp is self-hosted: the flagship deployment is a server on the user's own LAN. Requiring
 * `NET_CAPABILITY_VALIDATED` (Android's "my captive-portal probe reached the internet") made the
 * client call itself offline on exactly that network — no sync drain, no catch-up, no
 * fetch-on-miss — while the server sat two metres away on the same subnet.
 */
class NetworkStatusPolicyTest :
    FunSpec({

        test("an unvalidated LAN-only Wi-Fi is online") {
            val lanOnly = NetworkFacts(hasInternetRoute = true, isValidated = false, isUnmetered = true, isLocalTransport = true)
            NetworkStatusPolicy.isOnline(lanOnly) shouldBe true
        }

        test("an unvalidated unmetered network is still unmetered") {
            val lanOnly = NetworkFacts(hasInternetRoute = true, isValidated = false, isUnmetered = true, isLocalTransport = true)
            NetworkStatusPolicy.isUnmetered(lanOnly) shouldBe true
        }

        test("a validated network is online, unchanged") {
            val validated = NetworkFacts(hasInternetRoute = true, isValidated = true, isUnmetered = false, isLocalTransport = true)
            NetworkStatusPolicy.isOnline(validated) shouldBe true
            NetworkStatusPolicy.isUnmetered(validated) shouldBe false
        }

        test("no route means offline and metered, whatever else is true") {
            val noRoute = NetworkFacts(hasInternetRoute = false, isValidated = true, isUnmetered = true, isLocalTransport = true)
            NetworkStatusPolicy.isOnline(noRoute) shouldBe false
            NetworkStatusPolicy.isUnmetered(noRoute) shouldBe false
        }

        test("no active network is offline") {
            NetworkStatusPolicy.isOnline(NetworkFacts.NONE) shouldBe false
            NetworkStatusPolicy.isUnmetered(NetworkFacts.NONE) shouldBe false
        }

        // A home Wi-Fi whose internet is down leaves cellular as the active network, and a Wi-Fi
        // can be marked metered; either way the server is still on the LAN and can be searched for.
        test("a Wi-Fi beside an active cellular network is a local network") {
            val cellular = NetworkFacts(hasInternetRoute = true, isValidated = true, isUnmetered = false, isLocalTransport = false)
            val wanlessWifi = NetworkFacts(hasInternetRoute = true, isValidated = false, isUnmetered = true, isLocalTransport = true)
            NetworkStatusPolicy.isOnLocalNetwork(listOf(cellular, wanlessWifi)) shouldBe true
        }

        test("a metered Wi-Fi is still a local network") {
            val meteredWifi = NetworkFacts(hasInternetRoute = true, isValidated = true, isUnmetered = false, isLocalTransport = true)
            NetworkStatusPolicy.isOnLocalNetwork(listOf(meteredWifi)) shouldBe true
        }

        test("cellular alone, or nothing, is no local network") {
            val cellular = NetworkFacts(hasInternetRoute = true, isValidated = true, isUnmetered = false, isLocalTransport = false)
            NetworkStatusPolicy.isOnLocalNetwork(listOf(cellular)) shouldBe false
            NetworkStatusPolicy.isOnLocalNetwork(emptyList()) shouldBe false
        }
    })
