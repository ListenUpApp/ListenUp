package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.domain.repository.StreamedRegistrationStatus
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for the RPC-backed [RegistrationStatusStreamImpl], replacing the retired
 * `RegistrationStreamSseTest`'s status-stream coverage now that the transport is
 * `AuthServicePublic.observeRegistrationStatus` — a flow that emits then COMPLETES on a terminal
 * status, never a serve-and-close connection to mistake for a healthy one.
 */
class RegistrationStreamRpcTest :
    FunSpec({

        fun channelFor(service: AuthServicePublic): RpcChannel<AuthServicePublic> = RpcChannel.forTest(service)

        test("streamStatus emits Pending then Approved, mirroring the server's Data events") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observeRegistrationStatus("user-1") } returns
                    flowOf(
                        RpcEvent.Data(RegistrationStatusEvent(status = "pending")),
                        RpcEvent.Data(RegistrationStatusEvent(status = "approved")),
                    )
                val impl = RegistrationStatusStreamImpl(channelFor(service))

                val statuses = impl.streamStatus("user-1").toList()

                statuses shouldContainExactly
                    listOf(StreamedRegistrationStatus.Pending, StreamedRegistrationStatus.Approved)
            }
        }

        test("streamStatus surfaces a denied decision with its message") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observeRegistrationStatus("user-1") } returns
                    flowOf(RpcEvent.Data(RegistrationStatusEvent(status = "denied", message = "not eligible")))
                val impl = RegistrationStatusStreamImpl(channelFor(service))

                val statuses = impl.streamStatus("user-1").toList()

                statuses shouldContainExactly listOf(StreamedRegistrationStatus.Denied("not eligible"))
            }
        }

        test("streamStatus THROWS on a business RpcEvent.Error — never silently drops it") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observeRegistrationStatus("ghost") } returns
                    flowOf(RpcEvent.Error(AuthError.RegistrationNotFound()))
                val impl = RegistrationStatusStreamImpl(channelFor(service))

                val failure =
                    shouldThrow<RegistrationStatusStreamFailure> {
                        impl.streamStatus("ghost").toList()
                    }
                failure.error.shouldBeInstanceOf<AuthError.RegistrationNotFound>()
            }
        }

        test("streamStatus THROWS when the transport itself faults mid-stream — the ViewModel's retry path relies on it") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observeRegistrationStatus("user-1") } returns
                    flow {
                        emit(RpcEvent.Data(RegistrationStatusEvent(status = "pending")))
                        throw IllegalStateException("socket dropped")
                    }
                val impl = RegistrationStatusStreamImpl(channelFor(service))

                shouldThrow<RegistrationStatusStreamFailure> {
                    impl.streamStatus("user-1").toList()
                }
            }
        }

        test("fetchStatus takes the watch's first emission and never throws on a business error") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observeRegistrationStatus("ghost") } returns
                    flowOf(RpcEvent.Error(AuthError.RegistrationNotFound()))
                val impl = RegistrationStatusStreamImpl(channelFor(service))

                impl.fetchStatus("ghost") shouldBe StreamedRegistrationStatus.Pending
            }
        }

        test("fetchStatus resolves the persisted Approved status without waiting for a live push") {
            runTest {
                val service = mock<AuthServicePublic>()
                every { service.observeRegistrationStatus("user-1") } returns
                    flowOf(RpcEvent.Data(RegistrationStatusEvent(status = "approved")))
                val impl = RegistrationStatusStreamImpl(channelFor(service))

                impl.fetchStatus("user-1") shouldBe StreamedRegistrationStatus.Approved
            }
        }
    })
