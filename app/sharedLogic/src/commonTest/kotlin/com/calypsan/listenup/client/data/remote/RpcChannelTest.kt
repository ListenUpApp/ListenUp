package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.WebSocketException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

/** A minimal stand-in RPC service — one unary call, one stream — enough to exercise both channel doors. */
private interface FakeRpcService {
    suspend fun fetch(): AppResult<String>

    fun observe(): Flow<RpcEvent<Int>>
}

/**
 * Unit tests for [RpcChannel] via `forTest` (which routes through the production [catchingRpcResult]
 * boundary). Pins the three unary outcomes and the two streaming outcomes the whole layer relies on.
 */
class RpcChannelTest :
    FunSpec({

        test("call returns a service Success") {
            runTest {
                val service = mock<FakeRpcService>()
                everySuspend { service.fetch() } returns AppResult.Success("value")

                val result = RpcChannel.forTest(service).call { it.fetch() }

                result.shouldBeInstanceOf<AppResult.Success<String>>().data shouldBe "value"
            }
        }

        test("call passes a business Failure through untouched — never mistaken for a transport fault") {
            runTest {
                val service = mock<FakeRpcService>()
                everySuspend { service.fetch() } returns AppResult.Failure(ValidationError(message = "nope"))

                val result = RpcChannel.forTest(service).call { it.fetch() }

                result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<ValidationError>()
            }
        }

        test("call folds a thrown transport fault to a typed AppResult.Failure via the real boundary") {
            runTest {
                val service = mock<FakeRpcService>()
                everySuspend { service.fetch() } throws IOException("socket reset")

                val result = RpcChannel.forTest(service).call { it.fetch() }

                result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
            }
        }

        test("stream passes Data events through unchanged") {
            runTest {
                val service = mock<FakeRpcService>()
                every { service.observe() } returns flowOf(RpcEvent.Data(1), RpcEvent.Data(2))

                val events = RpcChannel.forTest(service).stream { it.observe() }.toList()

                events shouldContainExactly listOf(RpcEvent.Data(1), RpcEvent.Data(2))
            }
        }

        test("stream folds an upstream fault into a single terminal RpcEvent.Error") {
            runTest {
                val service = mock<FakeRpcService>()
                every { service.observe() } returns
                    flow {
                        emit(RpcEvent.Data(1))
                        throw IOException("stream dropped")
                    }

                val events = RpcChannel.forTest(service).stream { it.observe() }.toList()

                events.size shouldBe 2
                events[0] shouldBe RpcEvent.Data(1)
                val error = events[1].shouldBeInstanceOf<RpcEvent.Error>()
                error.error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
            }
        }

        // ─── W7 recursion firewall pins ──────────────────────────────────────────────────────
        //
        // The refresh primitive (AuthServicePublic.refreshSession) rides a Public channel. A Public
        // channel MUST NOT recover a handshake 401 (that would be refresh → 401 → refresh → ∞), so
        // its policy resolves RpcAuthRecovery.None in `rpcChannel`'s `when`. These two pins lock that
        // in: the policy knob that drives the wiring, and the behavioural fold of the exact fault.

        test("RpcPolicy.Public recovers via Public mode; Authed via Authed — the knob that wires None") {
            RpcPolicy.Public.recovery shouldBe RecoveryMode.Public
            RpcPolicy.Authed.recovery shouldBe RecoveryMode.Authed
        }

        test("a WS-handshake 401 on the Public refresh channel folds to a typed Failure — no throw, no loop") {
            runTest {
                // Script the exact WS-handshake-401 transport fault the refresh call would hit. On a
                // Public channel it must SURFACE as a typed AuthError.SessionExpired — not throw, and
                // not (as an Authed channel would) trigger a token refresh that re-enters this path.
                val channel =
                    RpcChannel.forTestScripted(
                        mock<AuthServicePublic>(),
                        faults = listOf(WebSocketException("expected status code 101 but was 401")),
                        policy = RpcPolicy.Public,
                    )

                val result = channel.call { it.refreshSession(RefreshRequest(RefreshToken("stale"))) }

                result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AuthError.SessionExpired>()
            }
        }
    })
