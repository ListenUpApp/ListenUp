package com.calypsan.listenup.client.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

/**
 * [AndroidLocalNetworkAccess] decides, after a connect has already failed, whether Android 17's
 * `ACCESS_LOCAL_NETWORK` gate is what stopped it. It is built from injected facts (SDK level,
 * grant state, a DNS resolver) so the decision runs on the host JVM without a device.
 */
class AndroidLocalNetworkAccessTest :
    FunSpec({
        val android16 = 36
        val android17 = 37
        val noResolver: suspend (String) -> List<String> = { error("resolver must not be consulted for $it") }

        test("Android 16 never gates a connection, even to the LAN") {
            val access = AndroidLocalNetworkAccess(sdkInt = android16, isGranted = { false }, resolveHost = noResolver)

            access.isDeniedFor("192.168.1.5", 8080) shouldBe false
        }

        test("Android 17 with the permission granted is not denied") {
            val access = AndroidLocalNetworkAccess(sdkInt = android17, isGranted = { true }, resolveHost = noResolver)

            access.isDeniedFor("192.168.1.5", 8080) shouldBe false
        }

        test("Android 17 without the permission is denied for a LAN address") {
            val access = AndroidLocalNetworkAccess(sdkInt = android17, isGranted = { false }, resolveHost = noResolver)

            access.isDeniedFor("192.168.1.5", 8080) shouldBe true
        }

        test("Android 17 without the permission is not denied for a public address") {
            val access = AndroidLocalNetworkAccess(sdkInt = android17, isGranted = { false }, resolveHost = noResolver)

            access.isDeniedFor("8.8.8.8", 443) shouldBe false
        }

        test("a mDNS name is on the LAN without a lookup, which the gate would block anyway") {
            val access = AndroidLocalNetworkAccess(sdkInt = android17, isGranted = { false }, resolveHost = noResolver)

            access.isDeniedFor("nas.local", 8080) shouldBe true
        }

        test("a plain hostname that resolves to a LAN address is denied") {
            val lookups = mutableListOf<String>()
            val access =
                AndroidLocalNetworkAccess(
                    sdkInt = android17,
                    isGranted = { false },
                    resolveHost = { host ->
                        lookups += host
                        listOf("192.168.86.250")
                    },
                )

            access.isDeniedFor("nas.lan", 8080) shouldBe true
            lookups shouldBe listOf("nas.lan")
        }

        test("a plain hostname that resolves only to public addresses is not denied") {
            val access =
                AndroidLocalNetworkAccess(
                    sdkInt = android17,
                    isGranted = { false },
                    resolveHost = { listOf("203.0.113.7") },
                )

            access.isDeniedFor("listenup.example.com", 443) shouldBe false
        }

        test("a hostname that does not resolve is not blamed on the permission") {
            val access =
                AndroidLocalNetworkAccess(
                    sdkInt = android17,
                    isGranted = { false },
                    resolveHost = { emptyList() },
                )

            access.isDeniedFor("nowhere.invalid", 443) shouldBe false
        }

        test("a granted permission is checked before any DNS lookup") {
            val lookups = mutableListOf<String>()
            val access =
                AndroidLocalNetworkAccess(
                    sdkInt = android17,
                    isGranted = { true },
                    resolveHost = { host ->
                        lookups += host
                        listOf("192.168.1.5")
                    },
                )

            access.isDeniedFor("nas.lan", 8080) shouldBe false
            lookups.shouldBeEmpty()
        }

        test("a resolver that never answers gives up rather than holding the connect screen") {
            // InetAddress lookups block on the system resolver; an unreachable DNS server would
            // otherwise keep "Verifying" up for a whole resolver timeout after the connect failed.
            runTest(timeout = 5.seconds) {
                val access =
                    AndroidLocalNetworkAccess(
                        sdkInt = android17,
                        isGranted = { false },
                        resolveHost = { awaitCancellation() },
                    )

                access.isDeniedFor("nas.lan", 8080) shouldBe false
            }
        }
    })
