@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.presentation.connection

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.data.connection.ConnectionEvidence
import com.calypsan.listenup.client.data.connection.ConnectionHealthStore
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.ConnectionHealth
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
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
import kotlinx.coroutines.flow.StateFlow
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

private class FakeNetworkMonitor : NetworkMonitor {
    override fun isOnline(): Boolean = true

    override val isOnlineFlow: StateFlow<Boolean> = MutableStateFlow(true)
    override val isOnUnmeteredNetworkFlow: StateFlow<Boolean> = MutableStateFlow(true)
}

private fun buildStore(
    scope: CoroutineScope,
    engineState: SyncEngineState = SyncEngineState(),
    authState: MutableStateFlow<AuthState> = MutableStateFlow(AuthState.Authenticated(u1, s1)),
    localPreferences: LocalPreferences = fakeLocalPreferences(),
    evidence: ConnectionEvidence = ConnectionEvidence(),
): ConnectionHealthStore =
    ConnectionHealthStore(
        engineState = engineState,
        authStateFlow = authState,
        errorBus = ErrorBus(),
        clientIdentity = FakeClientIdentity(),
        localPreferences = localPreferences,
        networkMonitor = FakeNetworkMonitor(),
        evidence = evidence,
        scope = scope,
    )

private val u1 = UserId("u1")
private val s1 = SessionId("s1")

class ConnectionHealthViewModelTest :
    FunSpec({
        beforeTest { Dispatchers.setMain(UnconfinedTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        test("ConnectionHealth maps to ConnectionHealthUi — domain Unreachable renders as Hidden") {
            runTest {
                val engineState = SyncEngineState() // starts Disconnected
                val authState = MutableStateFlow<AuthState>(AuthState.SessionLapsed(u1))
                val evidence = ConnectionEvidence()
                val store =
                    buildStore(
                        scope = backgroundScope,
                        engineState = engineState,
                        authState = authState,
                        evidence = evidence,
                    )
                store.reportCompat("drift")
                val viewModel = ConnectionHealthViewModel(healthStore = store)

                viewModel.state.test {
                    // Healthy → Hidden: the store's Eagerly seed value.
                    awaitItem() shouldBe ConnectionHealthUi.Hidden

                    // Genuine down evidence (a failed probe) arms Unreachable behind the lapse.
                    evidence.reportDown()
                    advanceTimeBy(3_100)
                    awaitItem() shouldBe ConnectionHealthUi.SessionExpired

                    // Re-authenticating reveals the domain Unreachable state — which the UI
                    // projection deliberately hides (offline-first: no ambient offline banner;
                    // point-of-need surfaces consume ServerReachability directly).
                    authState.value = AuthState.Authenticated(u1, s1)
                    awaitItem() shouldBe ConnectionHealthUi.Hidden
                    store.state.value.shouldBeInstanceOf<ConnectionHealth.Unreachable>()

                    store.reportProbe(true)
                    awaitItem() shouldBe ConnectionHealthUi.Outdated(clientVersion = "0.6.0", serverVersion = "?")

                    cancelAndConsumeRemainingEvents()
                }
            }
        }

        test("signIn emits NavigateToSignIn") {
            runTest {
                val store = buildStore(scope = backgroundScope)
                val viewModel = ConnectionHealthViewModel(healthStore = store)

                viewModel.events.test {
                    viewModel.signIn()
                    awaitItem() shouldBe ConnectionHealthViewModel.Event.NavigateToSignIn
                }
            }
        }

        test("dismiss delegates to ConnectionHealthStore.dismissOutdated") {
            runTest {
                val localPreferences =
                    fakeLocalPreferences(initialPeerServerVersion = "0.7.0", initialPeerServerApi = "v1")
                val store = buildStore(scope = backgroundScope, localPreferences = localPreferences)
                val viewModel = ConnectionHealthViewModel(healthStore = store)

                viewModel.dismiss()

                verifySuspend { localPreferences.setOutdatedDismissedFor("0.6.0" to "0.7.0") }
            }
        }
    })
