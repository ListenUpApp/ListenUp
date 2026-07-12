package com.calypsan.listenup.client.campfire

import app.cash.turbine.test
import com.calypsan.listenup.api.CampfireService
import com.calypsan.listenup.api.dto.campfire.CampfireAnchor
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfirePhase
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.CampfireSnapshot
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.error.CampfireError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.data.remote.CampfireRpcFactory
import com.calypsan.listenup.client.data.remote.catchingRpcResult
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest

/**
 * Fake [CampfireRpcFactory] that routes [CampfireRpcFactory.callResult] through the REAL
 * boundary [catchingRpcResult] — matches the
 * [com.calypsan.listenup.client.data.repository.PushRepositoryImplTest] precedent, so these
 * tests exercise the same throw→Failure / cancellation-rethrow semantics the production
 * [com.calypsan.listenup.client.data.remote.RpcProxyCache] engine provides, without a live
 * WebSocket.
 */
private class FakeCampfireRpcFactory(
    private val service: CampfireService,
) : CampfireRpcFactory {
    var invalidateCalls = 0

    override suspend fun get(): CampfireService = service

    override suspend fun <T> callResult(block: suspend (CampfireService) -> AppResult<T>): AppResult<T> = catchingRpcResult { block(service) }

    override suspend fun invalidate() {
        invalidateCalls += 1
    }
}

/**
 * Unit tests for [CampfireRpcTransport] — a thin mapping/delegation layer over
 * [CampfireService]. The Konsist rule `RpcFactoriesDelegateToRpcProxyCacheRule` is the
 * structural test for [KtorCampfireRpcFactory]'s own compliance; these tests cover the
 * transport's forwarding + [CampfireTransport.observeSession] passthrough behavior.
 */
class CampfireRpcTransportTest :
    FunSpec({

        fun stubSnapshot(id: String = "cf-1") =
            CampfireSnapshot(
                id = CampfireId(id),
                bookId = "book-1",
                settings = CampfireSettings(name = "Campfire", controlMode = CampfireControlMode.HOST_ONLY, inviteOnly = false),
                phase = CampfirePhase.LIVE,
                anchor =
                    CampfireAnchor(
                        positionMs = 0L,
                        capturedAtEpochMs = 0L,
                        speed = 1.0f,
                        isPlaying = false,
                        stateVersion = 0L,
                    ),
                members = emptyList(),
                hostUserId = "user-1",
                recentChat = emptyList(),
                yourPositionMs = null,
                spoilerAhead = false,
            )

        test("createSession delegates to CampfireService and returns the wire Success untouched") {
            runTest {
                val snapshot = stubSnapshot()
                val settings = CampfireSettings(name = "Campfire", controlMode = CampfireControlMode.EVERYONE, inviteOnly = false)
                val service =
                    mock<CampfireService> {
                        everySuspend { createSession("book-1", settings) } returns AppResult.Success(snapshot)
                    }
                val transport = CampfireRpcTransport(FakeCampfireRpcFactory(service))

                val result = transport.createSession("book-1", settings)

                result.shouldBeInstanceOf<AppResult.Success<CampfireSnapshot>>()
                result.data shouldBe snapshot
                verifySuspend { service.createSession("book-1", settings) }
            }
        }

        test("sendCommand passes a business failure through untouched") {
            runTest {
                val failure = AppResult.Failure(CampfireError.NotController())
                val command = PlaybackCommand.Pause(commandId = "cmd-1")
                val service =
                    mock<CampfireService> {
                        everySuspend { sendCommand(CampfireId("cf-1"), command) } returns failure
                    }
                val transport = CampfireRpcTransport(FakeCampfireRpcFactory(service))

                val result = transport.sendCommand(CampfireId("cf-1"), command)

                result shouldBe failure
            }
        }

        test("listOpenSessions delegates to CampfireService") {
            runTest {
                val service =
                    mock<CampfireService> {
                        everySuspend { listOpenSessions() } returns AppResult.Success(emptyList())
                    }
                val transport = CampfireRpcTransport(FakeCampfireRpcFactory(service))

                val result = transport.listOpenSessions()

                result.shouldBeInstanceOf<AppResult.Success<List<*>>>()
                verifySuspend { service.listOpenSessions() }
            }
        }

        test("refreshConnection invalidates the cached RPC proxy so the next use reconnects") {
            runTest {
                val service = mock<CampfireService>()
                val factory = FakeCampfireRpcFactory(service)
                val transport = CampfireRpcTransport(factory)

                transport.refreshConnection()

                factory.invalidateCalls shouldBe 1
            }
        }

        test("observeSession passes through the raw RpcEvent stream untouched — no unwrapping") {
            runTest {
                val hotFlow = MutableSharedFlow<RpcEvent<CampfireFrame>>()
                val service =
                    mock<CampfireService> {
                        every { observeSession(CampfireId("cf-1")) } returns hotFlow
                    }
                val transport = CampfireRpcTransport(FakeCampfireRpcFactory(service))

                transport.observeSession(CampfireId("cf-1")).test {
                    val hostChanged = RpcEvent.Data(CampfireFrame.HostChanged("user-2"))
                    hotFlow.emit(hostChanged)
                    awaitItem() shouldBe hostChanged

                    val errorEvent = RpcEvent.Error(InternalError())
                    hotFlow.emit(errorEvent)
                    awaitItem() shouldBe errorEvent

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
