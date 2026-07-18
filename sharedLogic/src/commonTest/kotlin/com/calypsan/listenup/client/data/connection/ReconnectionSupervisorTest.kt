@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SseClient
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.model.AuthState
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
import kotlinx.coroutines.flow.MutableStateFlow
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
                    every { authState } returns
                        MutableStateFlow<AuthState>(AuthState.Authenticated(UserId("u1"), SessionId("s1")))
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
            // First probe at t0; each failure escalates the wait BEFORE delaying, so the first
            // wait is already `interval * 2`. Advancing base + 2*base covers the first two waits.
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

        test("probe success while SessionLapsed does NOT kick reconnectNow (the spam amplifier)") {
            val scope = TestScope(StandardTestDispatcher())
            val engineState = SyncEngineState() // Disconnected — recovery loop runs
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
                    every { authState } returns
                        MutableStateFlow<AuthState>(AuthState.SessionLapsed(UserId("u1")))
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
            scope.testScheduler.runCurrent() // one probe succeeds
            engineState.setConnection(ConnectionState.Connected(lastEventId = 1L)) // end the loop
            scope.testScheduler.advanceUntilIdle()

            verifySuspend { instance.verifyServer(any()) } // it DID probe (reachability oracle)
            verify(exactly(0)) { sseClient.reconnectNow() } // but never amplified the 401 loop
            verifySuspend(exactly(0)) { authSession.clearAuthTokens() } // same instance — no wall
        }

        test("probe success reports reachable to the health store") {
            val scope = TestScope(StandardTestDispatcher())
            val engineState = SyncEngineState() // Disconnected — recovery loop runs
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
                    every { authState } returns
                        MutableStateFlow<AuthState>(AuthState.Authenticated(UserId("u1"), SessionId("s1")))
                    everySuspend { clearAuthTokens() } returns Unit
                }
            val reported = mutableListOf<Boolean>()
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
                    reportProbe = { reported.add(it) },
                )

            supervisor.start()
            scope.testScheduler.runCurrent() // one probe succeeds
            engineState.setConnection(ConnectionState.Connected(lastEventId = 1L)) // end the loop
            scope.testScheduler.advanceUntilIdle()

            reported shouldContain true
        }

        test("probe failure reports unreachable to the health store") {
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
            val reported = mutableListOf<Boolean>()
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
                    reportProbe = { reported.add(it) },
                )

            supervisor.start()
            // Don't advanceUntilIdle — the retry loop is infinite. Drive bounded virtual time.
            scope.testScheduler.advanceTimeBy(interval + interval * 2 + 1)
            scope.testScheduler.runCurrent()

            reported shouldContain false
        }

        test("a throw from reevaluate does not permanently stop recovery") {
            val scope = TestScope(StandardTestDispatcher())
            val engineState = SyncEngineState() // Disconnected
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
                    every { authState } returns
                        MutableStateFlow<AuthState>(AuthState.Authenticated(UserId("u1"), SessionId("s1")))
                    everySuspend { clearAuthTokens() } returns Unit
                }
            // reevaluate throws on the FIRST iteration (an mDNS/IO sweep failing during a network
            // change), then succeeds. Before the fix the throw ended the recovery loop for the
            // whole process lifetime — collectLatest only re-invokes it on a Disconnected->Connected
            // edge, which recovery itself is what produces — so reconnectNow() was never reached.
            var reevaluateCalls = 0
            val supervisor =
                ReconnectionSupervisor(
                    engineState = engineState,
                    instanceRepository = instance,
                    serverConfig = serverConfig,
                    sseClient = sseClient,
                    authSession = authSession,
                    errorBus = ErrorBus(),
                    reevaluate = { if (reevaluateCalls++ == 0) error("mDNS sweep blew up") },
                    scope = scope,
                    probeIntervalMillis = interval,
                )

            supervisor.start()
            // Advance past the throw's backoff (interval * 2) so the loop retries reevaluate
            // (now succeeds), probes, and reconnects.
            scope.testScheduler.advanceTimeBy(interval * 4)
            scope.testScheduler.runCurrent()

            verify(atLeast(1)) { sseClient.reconnectNow() }
        }

        test("a persistently-throwing reevaluate keeps retrying without hot-spinning") {
            val scope = TestScope(StandardTestDispatcher())
            val engineState = SyncEngineState() // Disconnected
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
                    every { authState } returns
                        MutableStateFlow<AuthState>(AuthState.Authenticated(UserId("u1"), SessionId("s1")))
                    everySuspend { clearAuthTokens() } returns Unit
                }
            var reevaluateCalls = 0
            val supervisor =
                ReconnectionSupervisor(
                    engineState = engineState,
                    instanceRepository = instance,
                    serverConfig = serverConfig,
                    sseClient = sseClient,
                    authSession = authSession,
                    errorBus = ErrorBus(),
                    reevaluate = {
                        reevaluateCalls++
                        error("mDNS down")
                    },
                    scope = scope,
                    probeIntervalMillis = interval,
                )

            supervisor.start()
            scope.testScheduler.runCurrent() // first iteration throws, backs off to interval * 2
            val afterFirst = reevaluateCalls
            // Advancing less than the backoff must NOT trigger another attempt (no hot-spin).
            scope.testScheduler.advanceTimeBy(interval)
            scope.testScheduler.runCurrent()
            check(reevaluateCalls == afterFirst) {
                "should back off, not hot-spin: $afterFirst -> $reevaluateCalls"
            }
            // After the backoff elapses it retries — recovery is still alive, not latched off.
            scope.testScheduler.advanceTimeBy(interval * 4)
            scope.testScheduler.runCurrent()
            check(reevaluateCalls > afterFirst) { "recovery should keep retrying after backoff" }
        }
    })
