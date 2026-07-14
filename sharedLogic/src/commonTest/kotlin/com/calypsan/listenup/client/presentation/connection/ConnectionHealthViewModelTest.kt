@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.presentation.connection

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.data.connection.ConnectionHealthStore
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.ConnectionHealth
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.Reachability
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.version.FakeClientIdentity
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * [ConnectionHealthStore] is `internal final class` — a same-module implementation detail with
 * no interface, so Mokkery can't subtype it for mocking (Kotlin/JVM can't subclass a final
 * type). Every test drives a real store over the same fakes as `ConnectionHealthStoreTest`,
 * asserting on the `ConnectionHealthViewModel`'s UI projection instead.
 */
private fun fakeLocalPreferences(
    initialPeerServerVersion: String? = null,
    initialPeerServerApi: String? = null,
): LocalPreferences =
    mock<LocalPreferences> {
        every { peerServerVersion } returns MutableStateFlow(initialPeerServerVersion)
        every { peerServerApi } returns MutableStateFlow(initialPeerServerApi)
        every { outdatedDismissedFor } returns MutableStateFlow(null)
        everySuspend { setOutdatedDismissedFor(any()) } returns Unit
    }

private fun buildStore(
    scope: CoroutineScope,
    engineState: SyncEngineState = SyncEngineState(),
    authState: MutableStateFlow<AuthState> = MutableStateFlow(AuthState.Authenticated(u1, s1)),
    localPreferences: LocalPreferences = fakeLocalPreferences(),
): ConnectionHealthStore =
    ConnectionHealthStore(
        engineState = engineState,
        authStateFlow = authState,
        errorBus = ErrorBus(),
        clientIdentity = FakeClientIdentity(),
        localPreferences = localPreferences,
        scope = scope,
    )

private val u1 = UserId("u1")
private val s1 = SessionId("s1")

class ConnectionHealthViewModelTest :
    FunSpec({
        beforeTest { Dispatchers.setMain(UnconfinedTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        test("ConnectionHealth maps to the matching ConnectionHealthUi case, precedence-ordered") {
            runTest {
                val engineState = SyncEngineState() // starts Disconnected
                val authState = MutableStateFlow<AuthState>(AuthState.SessionLapsed(u1))
                val store = buildStore(scope = backgroundScope, engineState = engineState, authState = authState)
                store.reportCompat("drift")
                val reachability = mock<ServerReachability>()
                val viewModel = ConnectionHealthViewModel(healthStore = store, serverReachability = reachability)

                viewModel.state.test {
                    // Healthy → Hidden: the store's Eagerly seed value, captured before its
                    // derivation coroutine has run.
                    awaitItem() shouldBe ConnectionHealthUi.Hidden

                    advanceTimeBy(3_100)
                    val unreachableUi = awaitItem()
                    unreachableUi.shouldBeInstanceOf<ConnectionHealthUi.Unreachable>()
                    val unreachableDomain = store.state.value
                    unreachableDomain.shouldBeInstanceOf<ConnectionHealth.Unreachable>()
                    (unreachableUi as ConnectionHealthUi.Unreachable).sinceMillis shouldBe
                        (unreachableDomain as ConnectionHealth.Unreachable).sinceMillis

                    store.reportProbe(true)
                    awaitItem() shouldBe ConnectionHealthUi.SessionExpired

                    authState.value = AuthState.Authenticated(u1, s1)
                    awaitItem() shouldBe ConnectionHealthUi.Outdated(clientVersion = "0.6.0", serverVersion = "?")

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("signIn emits NavigateToSignIn") {
            runTest {
                val store = buildStore(scope = backgroundScope)
                val reachability = mock<ServerReachability>()
                val viewModel = ConnectionHealthViewModel(healthStore = store, serverReachability = reachability)

                viewModel.events.test {
                    viewModel.signIn()
                    awaitItem() shouldBe ConnectionHealthViewModel.Event.NavigateToSignIn
                }
            }
        }

        test("retry delegates to ServerReachability.retry") {
            runTest {
                val store = buildStore(scope = backgroundScope)
                val reachability =
                    mock<ServerReachability> {
                        every { state } returns MutableStateFlow(Reachability.Unreachable)
                        everySuspend { retry() } returns Unit
                    }
                val viewModel = ConnectionHealthViewModel(healthStore = store, serverReachability = reachability)

                viewModel.retry()

                verifySuspend { reachability.retry() }
            }
        }

        test("dismiss delegates to ConnectionHealthStore.dismissOutdated") {
            runTest {
                val localPreferences =
                    fakeLocalPreferences(initialPeerServerVersion = "0.7.0", initialPeerServerApi = "v1")
                val store = buildStore(scope = backgroundScope, localPreferences = localPreferences)
                val reachability = mock<ServerReachability>()
                val viewModel = ConnectionHealthViewModel(healthStore = store, serverReachability = reachability)

                viewModel.dismiss()

                verifySuspend { localPreferences.setOutdatedDismissedFor("0.6.0" to "0.7.0") }
            }
        }
    })
