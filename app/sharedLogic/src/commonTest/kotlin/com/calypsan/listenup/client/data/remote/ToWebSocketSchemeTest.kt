package com.calypsan.listenup.client.data.remote

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins [toWebSocketScheme] semantics — kotlinx.rpc 0.10.x doesn't auto-upgrade
 * http:// to ws:// at the `client.rpc(url)` call site. The translation is the
 * production seam that makes RPC actually work; F12 covers the round-trip
 * scenario, this test pins the unit cases.
 */
class ToWebSocketSchemeTest :
    FunSpec({

        test("http upgrades to ws") {
            toWebSocketScheme("http://server.example.com/api/rpc/public") shouldBe
                "ws://server.example.com/api/rpc/public"
        }

        test("https upgrades to wss") {
            toWebSocketScheme("https://server.example.com/api/rpc/public") shouldBe
                "wss://server.example.com/api/rpc/public"
        }

        test("ports are preserved across the scheme swap") {
            toWebSocketScheme("http://127.0.0.1:8080") shouldBe "ws://127.0.0.1:8080"
            toWebSocketScheme("https://127.0.0.1:8443") shouldBe "wss://127.0.0.1:8443"
        }

        test("ws is passed through unchanged") {
            toWebSocketScheme("ws://server.example.com") shouldBe "ws://server.example.com"
        }

        test("wss is passed through unchanged") {
            toWebSocketScheme("wss://server.example.com") shouldBe "wss://server.example.com"
        }

        // Typed, not a bare IllegalStateException: ErrorMapper folds this into
        // ServerConnectError.InvalidUrl so the user is told their URL is wrong rather than that
        // the network is down. A stringly `error(...)` here escaped the data layer untyped,
        // breaking the AppResult contract.
        test("unsupported scheme throws the typed scheme exception") {
            val ex =
                shouldThrow<ServerUrlSchemeUnsupportedException> {
                    toWebSocketScheme("ftp://server.example.com")
                }
            ex.url shouldBe "ftp://server.example.com"
            ex.message shouldBe "Server URL has unsupported scheme: ftp://server.example.com"
        }

        test("scheme matching is case-sensitive — uppercase is not auto-recognized") {
            // The contract `ServerConfig.getActiveUrl()` always emits lowercase
            // schemes (it stores normalized URLs), so we don't accept uppercase
            // variants here. If that contract loosens, the test will catch it.
            shouldThrow<ServerUrlSchemeUnsupportedException> {
                toWebSocketScheme("HTTP://server.example.com")
            }
        }
    })
