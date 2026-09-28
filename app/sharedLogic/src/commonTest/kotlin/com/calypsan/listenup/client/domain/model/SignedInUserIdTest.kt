package com.calypsan.listenup.client.domain.model

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * [signedInUserId] is who "me" is for anything that marks the listener's own rows: it waits out
 * startup rather than saying "nobody" first, so a screen never flashes as if you were someone else.
 */
class SignedInUserIdTest :
    FunSpec({
        test("says nothing while auth is still initializing, then names the signed-in listener") {
            runTest {
                val auth = MutableStateFlow<AuthState>(AuthState.Initializing)

                auth.signedInUserId().test {
                    expectNoEvents()
                    auth.value = AuthState.Authenticated(UserId("me"), SessionId("s"))
                    awaitItem() shouldBe "me"
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a lapsed session is still you; signing out is nobody") {
            runTest {
                val auth = MutableStateFlow<AuthState>(AuthState.SessionLapsed(UserId("me")))

                auth.signedInUserId().test {
                    awaitItem() shouldBe "me"
                    auth.value = AuthState.NeedsLogin()
                    awaitItem() shouldBe null
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
