@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.connection

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.ConnectionHealth
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.version.FakeClientIdentity
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

private fun authed() = AuthState.Authenticated(UserId("u1"), SessionId("s1"))

private fun lapsed() = AuthState.SessionLapsed(UserId("u1"))

private fun fakeLocalPreferences(): LocalPreferences =
    mock<LocalPreferences> {
        every { peerServerVersion } returns MutableStateFlow(null)
        every { peerServerApi } returns MutableStateFlow(null)
        every { outdatedDismissedFor } returns MutableStateFlow(null)
    }

private class FakeNetworkMonitor(
    initialOnline: Boolean = true,
) : NetworkMonitor {
    val online = MutableStateFlow(initialOnline)

    override fun isOnline(): Boolean = online.value

    override val isOnlineFlow: StateFlow<Boolean> get() = online
    override val isOnUnmeteredNetworkFlow: StateFlow<Boolean> = MutableStateFlow(true)
}

private fun CoroutineScope.buildStore(
    engineState: SyncEngineState = SyncEngineState(),
    authState: MutableStateFlow<AuthState> = MutableStateFlow(authed()),
    errorBus: ErrorBus = ErrorBus(),
    localPreferences: LocalPreferences = fakeLocalPreferences(),
    networkMonitor: NetworkMonitor = FakeNetworkMonitor(),
    evidence: ConnectionEvidence = ConnectionEvidence(),
    clientIdentity: FakeClientIdentity = FakeClientIdentity(),
): ConnectionHealthStore =
    ConnectionHealthStore(
        engineState = engineState,
        authStateFlow = authState,
        errorBus = errorBus,
        clientIdentity = clientIdentity,
        localPreferences = localPreferences,
        networkMonitor = networkMonitor,
        evidence = evidence,
        scope = this,
    )

class ConnectionHealthStoreTest :
    FunSpec({

        // ========== Evidence-based reachability ==========

        test("device network loss surfaces Unreachable after debounce; heals instantly on regain") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                val network = FakeNetworkMonitor(initialOnline = true)
                val store = backgroundScope.buildStore(engineState = engineState, networkMonitor = network)

                store.state.test {
                    awaitItem() shouldBe ConnectionHealth.Healthy // eager seed

                    // Airplane mode: the device itself says there is no network. Nothing else —
                    // not even a connected-looking firehose snapshot — may override that.
                    network.online.value = false
                    advanceTimeBy(2_000)
                    expectNoEvents() // inside the debounce, a blip must not surface

                    advanceTimeBy(1_200)
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.Unreachable>()

                    network.online.value = true
                    awaitItem() shouldBe ConnectionHealth.Healthy // heals instantly, no debounce out

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("down evidence with a down firehose surfaces Unreachable; fresh up evidence heals instantly") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Disconnected(reason = "transport"))
                val evidence = ConnectionEvidence()
                val store = backgroundScope.buildStore(engineState = engineState, evidence = evidence)

                store.state.test {
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    // A real network-class failure (failed probe / failed RPC) while the firehose
                    // is also down: this is genuine unreachability evidence.
                    evidence.reportDown()
                    advanceTimeBy(3_100)
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.Unreachable>()

                    // Any later proof the server answered — a successful RPC, a positive probe —
                    // heals the reading the instant it happens.
                    evidence.reportUp()
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("a wedged firehose with successful traffic stays Healthy — traffic outranks the stream") {
            runTest {
                // THE production bug this model exists to kill: the SSE firehose is wedged
                // (down with a reason, never recovering) while unary RPC works fine — content
                // syncs, playback streams. The old grace-timer model surfaced a false permanent
                // "offline" here; real traffic evidence must keep health green.
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Disconnected(reason = "transport"))
                val evidence = ConnectionEvidence()
                val store = backgroundScope.buildStore(engineState = engineState, evidence = evidence)

                store.state.test {
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    evidence.reportDown() // one stale failure...
                    evidence.recordOutcome(AppResult.Success(Unit)) // ...then real traffic succeeds
                    advanceTimeBy(60_000)
                    expectNoEvents() // stays Healthy indefinitely — no grace window to outwait

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("a down firehose alone is never Unreachable — it is not reachability evidence") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Disconnected(reason = "transport"))
                val store = backgroundScope.buildStore(engineState = engineState)

                store.state.test {
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    // No down evidence exists — only the stream's own opinion of itself. The
                    // supervisor's probes will produce real evidence either way within seconds;
                    // until then the state must not lie toward offline.
                    advanceTimeBy(120_000)
                    expectNoEvents()

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("a connected firehose short-circuits stale down evidence to Healthy") {
            runTest {
                val engineState = SyncEngineState()
                val evidence = ConnectionEvidence()
                evidence.reportDown() // stale down evidence from before the reconnect
                val store = backgroundScope.buildStore(engineState = engineState, evidence = evidence)

                store.state.test {
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    // A live firehose is a heartbeat-bearing TCP connection to the server — the
                    // strongest possible proof of reachability, regardless of older failures.
                    engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                    advanceTimeBy(60_000)
                    expectNoEvents()

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("recordOutcome: any server response is up evidence; only transport-class failures are down") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Disconnected(reason = "transport"))
                val evidence = ConnectionEvidence()
                val store = backgroundScope.buildStore(engineState = engineState, evidence = evidence)

                store.state.test {
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    // A typed BUSINESS failure still proves the server answered → no offline.
                    evidence.recordOutcome(AppResult.Failure(AuthError.SessionExpired()))
                    advanceTimeBy(10_000)
                    expectNoEvents()

                    // A transport-class failure is genuine down evidence.
                    evidence.recordOutcome(AppResult.Failure(TransportError.NetworkUnavailable()))
                    advanceTimeBy(3_100)
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.Unreachable>()

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("initial sync: pristine engine + no evidence stays Healthy indefinitely") {
            runTest {
                // The pristine engine start (`Disconnected(reason = null)`) is the ENTIRE initial
                // catch-up window — the firehose is down because it was never asked to connect.
                // With no down evidence, health must stay green with no probes required at all.
                val store = backgroundScope.buildStore(engineState = SyncEngineState())

                store.state.test {
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    advanceTimeBy(120_000)
                    expectNoEvents()

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("Unreachable debounces 3s and heals instantly via probe") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Disconnected(reason = "x"))
                val store = backgroundScope.buildStore(engineState = engineState)

                store.state.test {
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    store.reportProbe(false)
                    advanceTimeBy(2_000)
                    expectNoEvents()

                    advanceTimeBy(1_200)
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.Unreachable>()

                    store.reportProbe(true)
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        // ========== Precedence ==========

        test("precedence SessionExpired > Unreachable > Outdated, resolving as signals clear") {
            runTest {
                val engineState = SyncEngineState() // starts Disconnected
                val authState = MutableStateFlow<AuthState>(lapsed())
                val evidence = ConnectionEvidence()
                val store =
                    backgroundScope.buildStore(
                        engineState = engineState,
                        authState = authState,
                        evidence = evidence,
                    )
                store.reportCompat("drift")

                store.state.test {
                    // Turbine's subscription captures the still-unpumped `Eagerly` seed value
                    // before the internal derivation coroutine has had a chance to run.
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    // Down evidence + auth dead + compat drift all at once: SessionExpired wins.
                    // Unreachable has no ambient UI, so it must never mask the actionable
                    // sign-in prompt.
                    evidence.reportDown()
                    advanceTimeBy(3_100)
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.SessionExpired>()

                    // Re-authenticating reveals the armed Unreachable state.
                    authState.value = authed()
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.Unreachable>()

                    // Fresh up evidence clears Unreachable; compat drift remains.
                    store.reportProbe(true)
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.Outdated>()

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        // ========== Auth / error routing ==========

        test("auth error edges the ErrorBus exactly once; state → SessionExpired on lapse") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                val authState = MutableStateFlow<AuthState>(authed())
                val errorBus = ErrorBus()
                val store =
                    backgroundScope.buildStore(
                        engineState = engineState,
                        authState = authState,
                        errorBus = errorBus,
                    )

                errorBus.errors.test {
                    store.report(AuthError.SessionExpired())
                    store.report(AuthError.SessionExpired())
                    awaitItem()
                    expectNoEvents()

                    authState.value = lapsed()
                    advanceTimeBy(1)
                    cancelAndConsumeRemainingEvents()
                }

                store.state.value.shouldBeInstanceOf<ConnectionHealth.SessionExpired>()
            }
        }

        test("parse evidence never routes to auth") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                val errorBus = ErrorBus()
                val store = backgroundScope.buildStore(engineState = engineState, errorBus = errorBus)

                errorBus.errors.test {
                    store.reportCompat("envelope v=2")
                    expectNoEvents()
                    cancelAndConsumeRemainingEvents()
                }

                advanceTimeBy(1)
                store.state.value.shouldBeInstanceOf<ConnectionHealth.Outdated>()
            }
        }

        test("report(ContractMismatch) routes to compat, never auth") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                val errorBus = ErrorBus()
                val store = backgroundScope.buildStore(engineState = engineState, errorBus = errorBus)

                errorBus.errors.test {
                    store.report(TransportError.ContractMismatch(detail = "envelope v=2"))
                    expectNoEvents()
                    cancelAndConsumeRemainingEvents()
                }

                advanceTimeBy(1)
                store.state.value.shouldBeInstanceOf<ConnectionHealth.Outdated>()
            }
        }

        test("report(DataMalformed) routes to compat, never auth") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                val errorBus = ErrorBus()
                val store = backgroundScope.buildStore(engineState = engineState, errorBus = errorBus)

                errorBus.errors.test {
                    store.report(TransportError.DataMalformed(detail = "bad body"))
                    expectNoEvents()
                    cancelAndConsumeRemainingEvents()
                }

                advanceTimeBy(1)
                store.state.value.shouldBeInstanceOf<ConnectionHealth.Outdated>()
            }
        }

        // ========== Version compat ==========

        test("API contract mismatch alone surfaces Outdated") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                val peerVersion = MutableStateFlow<String?>(null)
                val peerApi = MutableStateFlow<String?>(null)
                val localPreferences =
                    mock<LocalPreferences> {
                        every { peerServerVersion } returns peerVersion
                        every { peerServerApi } returns peerApi
                        every { outdatedDismissedFor } returns MutableStateFlow(null)
                    }
                val store =
                    backgroundScope.buildStore(
                        engineState = engineState,
                        localPreferences = localPreferences,
                        clientIdentity = FakeClientIdentity(version = "0.6.0", apiVersion = "v1"),
                    )

                store.state.test {
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    peerVersion.value = "0.6.0"
                    peerApi.value = "v2"
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.Outdated>()

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("major-version gap surfaces Outdated with no behavioural evidence") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                val peerVersion = MutableStateFlow<String?>(null)
                val localPreferences =
                    mock<LocalPreferences> {
                        every { peerServerVersion } returns peerVersion
                        every { peerServerApi } returns MutableStateFlow(null)
                        every { outdatedDismissedFor } returns MutableStateFlow(null)
                    }
                val store =
                    backgroundScope.buildStore(
                        engineState = engineState,
                        localPreferences = localPreferences,
                        clientIdentity = FakeClientIdentity(version = "0.6.0", apiVersion = "v1"),
                    )

                store.state.test {
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    peerVersion.value = "1.0.0"
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.Outdated>()

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("minor/patch skew alone stays Healthy") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                val peerVersion = MutableStateFlow<String?>(null)
                val localPreferences =
                    mock<LocalPreferences> {
                        every { peerServerVersion } returns peerVersion
                        every { peerServerApi } returns MutableStateFlow(null)
                        every { outdatedDismissedFor } returns MutableStateFlow(null)
                    }
                val store =
                    backgroundScope.buildStore(
                        engineState = engineState,
                        localPreferences = localPreferences,
                        clientIdentity = FakeClientIdentity(version = "0.6.0", apiVersion = "v1"),
                    )

                store.state.test {
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    peerVersion.value = "0.9.9"
                    expectNoEvents()

                    cancelAndConsumeRemainingEvents()
                }

                store.state.value shouldBe ConnectionHealth.Healthy
            }
        }

        test("dismissing the gap for the exact (client, server) pair returns to Healthy") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                val peerVersion = MutableStateFlow<String?>("1.0.0")
                val dismissedFor = MutableStateFlow<Pair<String, String>?>(null)
                val localPreferences =
                    mock<LocalPreferences> {
                        every { peerServerVersion } returns peerVersion
                        every { peerServerApi } returns MutableStateFlow(null)
                        every { outdatedDismissedFor } returns dismissedFor
                    }
                val store =
                    backgroundScope.buildStore(
                        engineState = engineState,
                        localPreferences = localPreferences,
                        clientIdentity = FakeClientIdentity(version = "0.6.0", apiVersion = "v1"),
                    )

                store.state.test {
                    // Turbine's subscription captures the still-unpumped `Eagerly` seed value
                    // before the internal derivation coroutine has had a chance to run.
                    awaitItem() shouldBe ConnectionHealth.Healthy
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.Outdated>()

                    dismissedFor.value = "0.6.0" to "1.0.0"
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("a server upgrade past the gap re-evaluates to Healthy") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                val peerVersion = MutableStateFlow<String?>("1.0.0")
                val localPreferences =
                    mock<LocalPreferences> {
                        every { peerServerVersion } returns peerVersion
                        every { peerServerApi } returns MutableStateFlow(null)
                        every { outdatedDismissedFor } returns MutableStateFlow(null)
                    }
                val store =
                    backgroundScope.buildStore(
                        engineState = engineState,
                        localPreferences = localPreferences,
                        clientIdentity = FakeClientIdentity(version = "0.6.0", apiVersion = "v1"),
                    )

                store.state.test {
                    // Turbine's subscription captures the still-unpumped `Eagerly` seed value
                    // before the internal derivation coroutine has had a chance to run.
                    awaitItem() shouldBe ConnectionHealth.Healthy
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.Outdated>()

                    peerVersion.value = "0.9.9"
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    cancelAndConsumeRemainingEvents()
                }
            }
        }
    })
