@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.auth

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class RegistrationPolicyBroadcasterTest :
    FunSpec({

        test("notify delivers the policy to a live subscriber") {
            runTest {
                val broadcaster = RegistrationPolicyBroadcaster()
                val sub = async { broadcaster.subscribe().first() }
                advanceUntilIdle()

                broadcaster.notify(RegistrationPolicy.CLOSED)

                sub.await() shouldBe RegistrationPolicy.CLOSED
            }
        }

        test("every live subscriber receives the notification") {
            runTest {
                val broadcaster = RegistrationPolicyBroadcaster()
                val sub1 = async { broadcaster.subscribe().first() }
                val sub2 = async { broadcaster.subscribe().first() }
                advanceUntilIdle()

                broadcaster.notify(RegistrationPolicy.OPEN)

                sub1.await() shouldBe RegistrationPolicy.OPEN
                sub2.await() shouldBe RegistrationPolicy.OPEN
            }
        }

        test("notify with no subscribers is a no-op (does not throw, nothing replayed)") {
            runTest {
                val broadcaster = RegistrationPolicyBroadcaster()
                broadcaster.notify(RegistrationPolicy.CLOSED)

                // replay = 0: a later subscriber sees nothing — the drop was a true no-op.
                broadcaster.subscribe().test {
                    expectNoEvents()
                }
            }
        }
    })
