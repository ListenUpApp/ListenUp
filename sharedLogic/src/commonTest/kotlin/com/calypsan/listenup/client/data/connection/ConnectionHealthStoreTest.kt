@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.data.connection

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.ConnectionHealth
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.version.FakeClientIdentity
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

class ConnectionHealthStoreTest :
    FunSpec({

        test("auth error edges the ErrorBus exactly once; state → SessionExpired on lapse") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                val authState = MutableStateFlow<AuthState>(authed())
                val errorBus = ErrorBus()
                val store =
                    ConnectionHealthStore(
                        engineState = engineState,
                        authStateFlow = authState,
                        errorBus = errorBus,
                        clientIdentity = FakeClientIdentity(),
                        localPreferences = fakeLocalPreferences(),
                        scope = backgroundScope,
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
                val authState = MutableStateFlow<AuthState>(authed())
                val errorBus = ErrorBus()
                val store =
                    ConnectionHealthStore(
                        engineState = engineState,
                        authStateFlow = authState,
                        errorBus = errorBus,
                        clientIdentity = FakeClientIdentity(),
                        localPreferences = fakeLocalPreferences(),
                        scope = backgroundScope,
                    )

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
                val authState = MutableStateFlow<AuthState>(authed())
                val errorBus = ErrorBus()
                val store =
                    ConnectionHealthStore(
                        engineState = engineState,
                        authStateFlow = authState,
                        errorBus = errorBus,
                        clientIdentity = FakeClientIdentity(),
                        localPreferences = fakeLocalPreferences(),
                        scope = backgroundScope,
                    )

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
                val authState = MutableStateFlow<AuthState>(authed())
                val errorBus = ErrorBus()
                val store =
                    ConnectionHealthStore(
                        engineState = engineState,
                        authStateFlow = authState,
                        errorBus = errorBus,
                        clientIdentity = FakeClientIdentity(),
                        localPreferences = fakeLocalPreferences(),
                        scope = backgroundScope,
                    )

                errorBus.errors.test {
                    store.report(TransportError.DataMalformed(detail = "bad body"))
                    expectNoEvents()
                    cancelAndConsumeRemainingEvents()
                }

                advanceTimeBy(1)
                store.state.value.shouldBeInstanceOf<ConnectionHealth.Outdated>()
            }
        }

        test("precedence Unreachable > SessionExpired > Outdated, resolving as signals clear") {
            runTest {
                val engineState = SyncEngineState() // starts Disconnected
                val authState = MutableStateFlow<AuthState>(lapsed())
                val store =
                    ConnectionHealthStore(
                        engineState = engineState,
                        authStateFlow = authState,
                        errorBus = ErrorBus(),
                        clientIdentity = FakeClientIdentity(),
                        localPreferences = fakeLocalPreferences(),
                        scope = backgroundScope,
                    )
                store.reportCompat("drift")

                store.state.test {
                    // Turbine's subscription captures the still-unpumped `Eagerly` seed value
                    // before the internal derivation coroutine has had a chance to run.
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    advanceTimeBy(3_100)
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.Unreachable>()

                    store.reportProbe(true)
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.SessionExpired>()

                    authState.value = authed()
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.Outdated>()

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("Unreachable debounces 3s and heals instantly") {
            runTest {
                val engineState = SyncEngineState() // starts Disconnected
                val authState = MutableStateFlow<AuthState>(authed())
                val store =
                    ConnectionHealthStore(
                        engineState = engineState,
                        authStateFlow = authState,
                        errorBus = ErrorBus(),
                        clientIdentity = FakeClientIdentity(),
                        localPreferences = fakeLocalPreferences(),
                        scope = backgroundScope,
                    )

                store.state.test {
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    advanceTimeBy(2_000)
                    engineState.setConnection(ConnectionState.Connected(lastEventId = null))

                    advanceTimeBy(100)
                    engineState.setConnection(ConnectionState.Disconnected(reason = "x"))

                    advanceTimeBy(2_000)
                    expectNoEvents()

                    advanceTimeBy(1_200)
                    awaitItem().shouldBeInstanceOf<ConnectionHealth.Unreachable>()

                    engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                    awaitItem() shouldBe ConnectionHealth.Healthy

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("API contract mismatch alone surfaces Outdated") {
            runTest {
                val engineState = SyncEngineState()
                engineState.setConnection(ConnectionState.Connected(lastEventId = null))
                val authState = MutableStateFlow<AuthState>(authed())
                val peerVersion = MutableStateFlow<String?>(null)
                val peerApi = MutableStateFlow<String?>(null)
                val localPreferences =
                    mock<LocalPreferences> {
                        every { peerServerVersion } returns peerVersion
                        every { peerServerApi } returns peerApi
                        every { outdatedDismissedFor } returns MutableStateFlow(null)
                    }
                val store =
                    ConnectionHealthStore(
                        engineState = engineState,
                        authStateFlow = authState,
                        errorBus = ErrorBus(),
                        clientIdentity = FakeClientIdentity(version = "0.6.0", apiVersion = "v1"),
                        localPreferences = localPreferences,
                        scope = backgroundScope,
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
                val authState = MutableStateFlow<AuthState>(authed())
                val peerVersion = MutableStateFlow<String?>(null)
                val localPreferences =
                    mock<LocalPreferences> {
                        every { peerServerVersion } returns peerVersion
                        every { peerServerApi } returns MutableStateFlow(null)
                        every { outdatedDismissedFor } returns MutableStateFlow(null)
                    }
                val store =
                    ConnectionHealthStore(
                        engineState = engineState,
                        authStateFlow = authState,
                        errorBus = ErrorBus(),
                        clientIdentity = FakeClientIdentity(version = "0.6.0", apiVersion = "v1"),
                        localPreferences = localPreferences,
                        scope = backgroundScope,
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
                val authState = MutableStateFlow<AuthState>(authed())
                val peerVersion = MutableStateFlow<String?>(null)
                val localPreferences =
                    mock<LocalPreferences> {
                        every { peerServerVersion } returns peerVersion
                        every { peerServerApi } returns MutableStateFlow(null)
                        every { outdatedDismissedFor } returns MutableStateFlow(null)
                    }
                val store =
                    ConnectionHealthStore(
                        engineState = engineState,
                        authStateFlow = authState,
                        errorBus = ErrorBus(),
                        clientIdentity = FakeClientIdentity(version = "0.6.0", apiVersion = "v1"),
                        localPreferences = localPreferences,
                        scope = backgroundScope,
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
                val authState = MutableStateFlow<AuthState>(authed())
                val peerVersion = MutableStateFlow<String?>("1.0.0")
                val dismissedFor = MutableStateFlow<Pair<String, String>?>(null)
                val localPreferences =
                    mock<LocalPreferences> {
                        every { peerServerVersion } returns peerVersion
                        every { peerServerApi } returns MutableStateFlow(null)
                        every { outdatedDismissedFor } returns dismissedFor
                    }
                val store =
                    ConnectionHealthStore(
                        engineState = engineState,
                        authStateFlow = authState,
                        errorBus = ErrorBus(),
                        clientIdentity = FakeClientIdentity(version = "0.6.0", apiVersion = "v1"),
                        localPreferences = localPreferences,
                        scope = backgroundScope,
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
                val authState = MutableStateFlow<AuthState>(authed())
                val peerVersion = MutableStateFlow<String?>("1.0.0")
                val localPreferences =
                    mock<LocalPreferences> {
                        every { peerServerVersion } returns peerVersion
                        every { peerServerApi } returns MutableStateFlow(null)
                        every { outdatedDismissedFor } returns MutableStateFlow(null)
                    }
                val store =
                    ConnectionHealthStore(
                        engineState = engineState,
                        authStateFlow = authState,
                        errorBus = ErrorBus(),
                        clientIdentity = FakeClientIdentity(version = "0.6.0", apiVersion = "v1"),
                        localPreferences = localPreferences,
                        scope = backgroundScope,
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
