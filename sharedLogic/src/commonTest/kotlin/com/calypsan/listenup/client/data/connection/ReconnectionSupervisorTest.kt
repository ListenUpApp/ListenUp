@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SseClient
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.VerifiedServer
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode.Companion.atLeast
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent

class ReconnectionSupervisorTest :
    FunSpec({

        val activeUrl = "http://192.168.1.10:8080"
        val interval = 5_000L

        fun serverInfo(instanceId: String) =
            ServerInfo(
                name = "ListenUp",
                version = "0.0.1",
                apiVersion = "v1",
                setupRequired = false,
                registrationPolicy = RegistrationPolicy.CLOSED,
                instanceId = instanceId,
            )

        fun verified(instanceId: String) = VerifiedServer(serverInfo = serverInfo(instanceId), verifiedUrl = activeUrl)

        test("disconnected + same instance triggers reconnectNow without logout") {
            val scope = TestScope(StandardTestDispatcher())
            val engineState = SyncEngineState() // starts Disconnected
            val instance =
                mock<InstanceRepository> {
                    everySuspend { verifyServer(any()) } returns AppResult.Success(verified("inst-1"))
                }
            val serverConfig =
                mock<ServerConfig> {
                    everySuspend { getActiveUrl() } returns ServerUrl(activeUrl)
                    everySuspend { getConnectedServerId() } returns "inst-1"
                }
            val sseClient =
                mock<SseClient> {
                    every { reconnectNow() } returns Unit
                }
            val authSession =
                mock<AuthSession> {
                    everySuspend { clearAuthTokens() } returns Unit
                }
            var reevaluateCount = 0
            val supervisor =
                ReconnectionSupervisor(
                    engineState = engineState,
                    instanceRepository = instance,
                    serverConfig = serverConfig,
                    sseClient = sseClient,
                    authSession = authSession,
                    errorBus = ErrorBus(),
                    reevaluate = { reevaluateCount++ },
                    scope = scope,
                    probeIntervalMillis = interval,
                )

            supervisor.start()
            // Let one probe cycle run, then stop the loop by going Connected.
            scope.testScheduler.runCurrent()
            engineState.setConnection(ConnectionState.Connected(lastEventId = 1L))
            scope.testScheduler.advanceUntilIdle()

            verify { sseClient.reconnectNow() }
            verifySuspend(exactly(0)) { authSession.clearAuthTokens() }
            check(reevaluateCount >= 1) { "reevaluate should have run at least once" }
        }

        test("disconnected + different instance clears auth, emits ServerInstanceChanged, stops") {
            val scope = TestScope(StandardTestDispatcher())
            val engineState = SyncEngineState()
            val instance =
                mock<InstanceRepository> {
                    everySuspend { verifyServer(any()) } returns AppResult.Success(verified("inst-NEW"))
                }
            val serverConfig =
                mock<ServerConfig> {
                    everySuspend { getActiveUrl() } returns ServerUrl(activeUrl)
                    everySuspend { getConnectedServerId() } returns "inst-OLD"
                }
            val sseClient =
                mock<SseClient> {
                    every { reconnectNow() } returns Unit
                }
            val authSession =
                mock<AuthSession> {
                    everySuspend { clearAuthTokens() } returns Unit
                }
            val errorBus = ErrorBus()
            val emitted = mutableListOf<com.calypsan.listenup.api.error.AppError>()
            scope.backgroundScope.launch { errorBus.errors.collect { emitted.add(it) } }

            val supervisor =
                ReconnectionSupervisor(
                    engineState = engineState,
                    instanceRepository = instance,
                    serverConfig = serverConfig,
                    sseClient = sseClient,
                    authSession = authSession,
                    errorBus = errorBus,
                    reevaluate = { },
                    scope = scope,
                    probeIntervalMillis = interval,
                )

            supervisor.start()
            scope.testScheduler.advanceUntilIdle()

            verifySuspend { authSession.clearAuthTokens() }
            verify(exactly(0)) { sseClient.reconnectNow() }
            emitted.map { it.code } shouldContain AuthError.ServerInstanceChanged().code
        }

        test("connected stays idle: no probe, no reconnect") {
            val scope = TestScope(StandardTestDispatcher())
            val engineState = SyncEngineState()
            engineState.setConnection(ConnectionState.Connected(lastEventId = 1L))
            val instance =
                mock<InstanceRepository> {
                    everySuspend { verifyServer(any()) } returns AppResult.Success(verified("inst-1"))
                }
            val serverConfig =
                mock<ServerConfig> {
                    everySuspend { getActiveUrl() } returns ServerUrl(activeUrl)
                    everySuspend { getConnectedServerId() } returns "inst-1"
                }
            val sseClient =
                mock<SseClient> {
                    every { reconnectNow() } returns Unit
                }
            val authSession =
                mock<AuthSession> {
                    everySuspend { clearAuthTokens() } returns Unit
                }
            val supervisor =
                ReconnectionSupervisor(
                    engineState = engineState,
                    instanceRepository = instance,
                    serverConfig = serverConfig,
                    sseClient = sseClient,
                    authSession = authSession,
                    errorBus = ErrorBus(),
                    reevaluate = { },
                    scope = scope,
                    probeIntervalMillis = interval,
                )

            supervisor.start()
            scope.testScheduler.advanceUntilIdle()

            verifySuspend(exactly(0)) { instance.verifyServer(any()) }
            verify(exactly(0)) { sseClient.reconnectNow() }
        }

        test("unreachable server retries with escalating interval") {
            val scope = TestScope(StandardTestDispatcher())
            val engineState = SyncEngineState()
            val instance =
                mock<InstanceRepository> {
                    everySuspend { verifyServer(any()) } returns
                        AppResult.Failure(TransportError.NetworkUnavailable())
                }
            val serverConfig =
                mock<ServerConfig> {
                    everySuspend { getActiveUrl() } returns ServerUrl(activeUrl)
                    everySuspend { getConnectedServerId() } returns "inst-1"
                }
            val sseClient =
                mock<SseClient> {
                    every { reconnectNow() } returns Unit
                }
            val authSession =
                mock<AuthSession> {
                    everySuspend { clearAuthTokens() } returns Unit
                }
            val supervisor =
                ReconnectionSupervisor(
                    engineState = engineState,
                    instanceRepository = instance,
                    serverConfig = serverConfig,
                    sseClient = sseClient,
                    authSession = authSession,
                    errorBus = ErrorBus(),
                    reevaluate = { },
                    scope = scope,
                    probeIntervalMillis = interval,
                )

            supervisor.start()
            // Don't advanceUntilIdle — the retry loop is infinite. Drive bounded virtual time.
            // First probe at t0, then waits `interval` (base), then `interval * 2` (escalated).
            // Advancing base + 2*base covers at least the first two waits → 3 probes by then.
            scope.testScheduler.advanceTimeBy(interval + interval * 2 + 1)
            scope.testScheduler.runCurrent()

            verifySuspend(atLeast(2)) { instance.verifyServer(any()) }
        }

        test("no active url: returns early without probing or clearing auth") {
            val scope = TestScope(StandardTestDispatcher())
            val engineState = SyncEngineState() // Disconnected
            val instance =
                mock<InstanceRepository> {
                    everySuspend { verifyServer(any()) } returns AppResult.Success(verified("inst-1"))
                }
            val serverConfig =
                mock<ServerConfig> {
                    everySuspend { getActiveUrl() } returns null
                    everySuspend { getConnectedServerId() } returns null
                }
            val sseClient =
                mock<SseClient> {
                    every { reconnectNow() } returns Unit
                }
            val authSession =
                mock<AuthSession> {
                    everySuspend { clearAuthTokens() } returns Unit
                }
            val supervisor =
                ReconnectionSupervisor(
                    engineState = engineState,
                    instanceRepository = instance,
                    serverConfig = serverConfig,
                    sseClient = sseClient,
                    authSession = authSession,
                    errorBus = ErrorBus(),
                    reevaluate = { },
                    scope = scope,
                    probeIntervalMillis = interval,
                )

            supervisor.start()
            scope.testScheduler.advanceUntilIdle()

            verifySuspend(exactly(0)) { instance.verifyServer(any()) }
            verifySuspend(exactly(0)) { authSession.clearAuthTokens() }
            verify(exactly(0)) { sseClient.reconnectNow() }
        }

        test("loop is cancelled once the connection becomes Connected") {
            val scope = TestScope(StandardTestDispatcher())
            val engineState = SyncEngineState() // Disconnected
            // Keep failing so the loop would otherwise probe forever; count actual probes.
            var probeCount = 0
            val instance =
                mock<InstanceRepository> {
                    everySuspend { verifyServer(any()) } calls
                        {
                            probeCount++
                            AppResult.Failure(TransportError.NetworkUnavailable())
                        }
                }
            val serverConfig =
                mock<ServerConfig> {
                    everySuspend { getActiveUrl() } returns ServerUrl(activeUrl)
                    everySuspend { getConnectedServerId() } returns "inst-1"
                }
            val sseClient =
                mock<SseClient> {
                    every { reconnectNow() } returns Unit
                }
            val authSession =
                mock<AuthSession> {
                    everySuspend { clearAuthTokens() } returns Unit
                }
            val supervisor =
                ReconnectionSupervisor(
                    engineState = engineState,
                    instanceRepository = instance,
                    serverConfig = serverConfig,
                    sseClient = sseClient,
                    authSession = authSession,
                    errorBus = ErrorBus(),
                    reevaluate = { },
                    scope = scope,
                    probeIntervalMillis = interval,
                )

            supervisor.start()
            scope.testScheduler.runCurrent() // one probe runs
            engineState.setConnection(ConnectionState.Connected(lastEventId = 1L))
            scope.testScheduler.runCurrent() // let collectLatest cancel the loop

            // Capture the count after cancellation, then advance well past several intervals.
            // If the loop were still alive it would fire many more probes.
            val countAtConnect = probeCount
            scope.testScheduler.advanceTimeBy(interval * 10)
            scope.testScheduler.runCurrent()

            check(probeCount == countAtConnect) {
                "expected no further probes after Connected, but probeCount grew from " +
                    "$countAtConnect to $probeCount"
            }
        }
    })
