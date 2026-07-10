@file:OptIn(ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.presentation.connection

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class ConnectionHealthViewModelTest :
    FunSpec({
        beforeTest { Dispatchers.setMain(UnconfinedTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        test("SessionLapsed maps to the SessionExpired banner state; Authenticated maps to Hidden") {
            runTest {
                val session = FakeAuthSession(authState = AuthState.Authenticated(UserId("u1"), SessionId("s1")))
                val viewModel = ConnectionHealthViewModel(authSession = session)

                viewModel.state.test {
                    awaitItem() shouldBe ConnectionHealthUi.Hidden

                    session.authState.value = AuthState.SessionLapsed(UserId("u1"))
                    awaitItem() shouldBe ConnectionHealthUi.SessionExpired

                    session.authState.value = AuthState.Authenticated(UserId("u1"), SessionId("s2"))
                    awaitItem() shouldBe ConnectionHealthUi.Hidden
                }
            }
        }
    })
