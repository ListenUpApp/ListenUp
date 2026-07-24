package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import dev.mokkery.answering.returns
import dev.mokkery.answering.sequentiallyReturns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for the RPC-backed [RegistrationPolicyStreamImpl], replacing the retired
 * `RegistrationPolicyStreamSseTest` now that the transport is
 * `AuthServicePublic.observeRegistrationPolicy`. The behavioural contract carries over from the
 * SSE engine: the consumer ([AuthSessionStore]) collects an INFINITE flow, so a server-side error
 * or completion must resubscribe (with backoff) rather than terminate — a dropped watch heals
 * itself, and a mid-wait policy change is picked up on the next subscription's current-policy emit.
 */
class RegistrationPolicyStreamImplTest :
    FunSpec({

        fun implFor(service: AuthServicePublic): RegistrationPolicyStreamImpl = RegistrationPolicyStreamImpl(channel = RpcChannel.forTest(service))

        test("streamPolicy emits the current policy then each change, mirroring the server's Data events") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observeRegistrationPolicy() } returns
                    flowOf(
                        RpcEvent.Data(RegistrationPolicy.OPEN),
                        RpcEvent.Data(RegistrationPolicy.CLOSED),
                    )
                val impl = implFor(service)

                val policies = impl.streamPolicy().take(2).toList()

                policies shouldContainExactly listOf(RegistrationPolicy.OPEN, RegistrationPolicy.CLOSED)
            }
        }

        test("a server-surfaced RpcEvent.Error resubscribes with backoff — never a dead stream") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observeRegistrationPolicy() } sequentiallyReturns
                    listOf(
                        flowOf(RpcEvent.Error(TransportError.NetworkUnavailable())),
                        flowOf(RpcEvent.Data(RegistrationPolicy.CLOSED)),
                    )
                val impl = implFor(service)

                // Virtual time skips the backoff delay; a real hang would time runTest out.
                impl.streamPolicy().first() shouldBe RegistrationPolicy.CLOSED
            }
        }

        test("an upstream completion resubscribes — the consumer's flow stays infinite") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observeRegistrationPolicy() } sequentiallyReturns
                    listOf(
                        flowOf(RpcEvent.Data(RegistrationPolicy.OPEN)),
                        flowOf(RpcEvent.Data(RegistrationPolicy.CLOSED)),
                    )
                val impl = implFor(service)

                val policies = impl.streamPolicy().take(2).toList()

                policies shouldContainExactly listOf(RegistrationPolicy.OPEN, RegistrationPolicy.CLOSED)
            }
        }

        test("a transport fault mid-stream heals: the thrown fault folds to an Error and the loop resubscribes") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observeRegistrationPolicy() } sequentiallyReturns
                    listOf(
                        flow {
                            emit(RpcEvent.Data(RegistrationPolicy.OPEN))
                            throw IllegalStateException("socket dropped")
                        },
                        flowOf(RpcEvent.Data(RegistrationPolicy.CLOSED)),
                    )
                val impl = implFor(service)

                val policies = impl.streamPolicy().take(2).toList()

                policies shouldContainExactly listOf(RegistrationPolicy.OPEN, RegistrationPolicy.CLOSED)
            }
        }
    })
