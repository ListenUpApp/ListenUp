package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.SocketTicket
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * The browser cannot put an `Authorization` header on a WebSocket upgrade, so the authed mount's
 * URL carries a single-use ticket instead. These pins cover every branch of that decision — and,
 * more importantly, pin that what lands in the URL is a TICKET. A URL is written to a reverse-proxy
 * access log; a 15-minute JWT there would be a live credential on disk after every reconnect.
 *
 * [rpcMountUrl]'s `upgradeCarriesHeaders` is passed explicitly rather than left to the platform
 * default: this suite runs on JVM/Android, where the real value is `true`, and the branch that
 * matters is the one only a browser takes. The platform values themselves are `actual` constants
 * (one per source set) and the browser's is proved end-to-end by `:app:webApp`'s `AuthArcTest`,
 * which makes an authenticated RPC call from Chromium.
 */
class WsUpgradeHeadersTest :
    FunSpec({

        test("a header-less platform carries a ticket on the authed mount") {
            runTest {
                val url =
                    rpcMountUrl(
                        baseUrl = "ws://localhost:8080",
                        policy = RpcPolicy.Authed,
                        upgradeCarriesHeaders = false,
                    ) { "tKt2sM9" }

                url shouldBe "ws://localhost:8080/api/rpc/authed?ticket=tKt2sM9"
            }
        }

        test("the public mount never carries a credential, even on a header-less platform") {
            runTest {
                val url =
                    rpcMountUrl(
                        baseUrl = "ws://localhost:8080",
                        policy = RpcPolicy.Public,
                        upgradeCarriesHeaders = false,
                    ) { "tKt2sM9" }

                url shouldBe "ws://localhost:8080/api/rpc/public"
            }
        }

        test("a platform whose upgrade carries headers keeps everything out of the URL") {
            runTest {
                val url =
                    rpcMountUrl(
                        baseUrl = "wss://library.example",
                        policy = RpcPolicy.Authed,
                        upgradeCarriesHeaders = true,
                    ) { "tKt2sM9" }

                url shouldBe "wss://library.example/api/rpc/authed"
            }
        }

        test("no ticket means no query parameter — an anonymous handshake, not an empty one") {
            runTest {
                val url =
                    rpcMountUrl(
                        baseUrl = "ws://localhost:8080",
                        policy = RpcPolicy.Authed,
                        upgradeCarriesHeaders = false,
                    ) { null }

                url shouldBe "ws://localhost:8080/api/rpc/authed"
            }
        }

        test("the ticket is URL-encoded") {
            runTest {
                // Real tickets are base64url and need no escaping; this pins that a ticket which
                // somehow did could never break out of the query parameter.
                val url =
                    rpcMountUrl(
                        baseUrl = "ws://localhost:8080",
                        policy = RpcPolicy.Authed,
                        upgradeCarriesHeaders = false,
                    ) { "a+b/c=d&e" }

                url shouldBe "ws://localhost:8080/api/rpc/authed?ticket=a%2Bb%2Fc%3Dd%26e"
            }
        }

        test("the ticket is minted lazily — a header-carrying platform never asks for one") {
            runTest {
                var minted = 0

                rpcMountUrl(
                    baseUrl = "ws://localhost:8080",
                    policy = RpcPolicy.Authed,
                    upgradeCarriesHeaders = true,
                ) {
                    minted++
                    "tKt2sM9"
                }

                // Not just a wasted round trip: minting on a platform that doesn't need one would
                // spend a ticket nothing ever redeems.
                minted shouldBe 0
            }
        }

        test("minting trades the access token for the ticket the server issues") {
            runTest {
                val service =
                    mock<AuthServicePublic>(MockMode.autofill) {
                        everySuspend { issueSocketTicket("header.payload.signature") } returns
                            AppResult.Success(SocketTicket("tKt2sM9"))
                    }

                val ticket =
                    mintSocketTicket(
                        accessToken = { "header.payload.signature" },
                        authChannel = { RpcChannel.forTest(service) },
                    )

                ticket shouldBe "tKt2sM9"
            }
        }

        test("minting without a session never reaches the server") {
            runTest {
                var called = false
                val service =
                    mock<AuthServicePublic>(MockMode.autofill) {
                        everySuspend { issueSocketTicket(any()) } calls
                            {
                                called = true
                                AppResult.Success(SocketTicket("tKt2sM9"))
                            }
                    }

                val ticket = mintSocketTicket(accessToken = { null }, authChannel = { RpcChannel.forTest(service) })

                ticket.shouldBeNull()
                called shouldBe false
            }
        }

        test("a declined mint yields no ticket, so the upgrade 401s into the existing refresh path") {
            runTest {
                val service =
                    mock<AuthServicePublic>(MockMode.autofill) {
                        everySuspend { issueSocketTicket(any()) } returns
                            AppResult.Failure(AuthError.SessionExpired())
                    }

                val ticket =
                    mintSocketTicket(
                        accessToken = { "stale.token.here" },
                        authChannel = { RpcChannel.forTest(service) },
                    )

                // Not an exception and not a retry here — a ticket-less URL is a 401, and
                // RpcAuthRecovery already knows how to refresh and retry that exactly once.
                ticket.shouldBeNull()
            }
        }
    })
