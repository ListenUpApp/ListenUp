@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.auth

import app.cash.turbine.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class RegistrationBroadcasterTest :
    FunSpec({

        test("notify delivers to the right userId's subscriber only") {
            runTest {
                val broadcaster = RegistrationBroadcaster()
                broadcaster.subscribe("u2").test {
                    val u1 = async { broadcaster.subscribe("u1").first() }
                    advanceUntilIdle()

                    broadcaster.notify("u1", RegistrationDecision.Approved)

                    u1.await() shouldBe RegistrationDecision.Approved
                    expectNoEvents()
                }
            }
        }

        test("two subscribers for one userId both receive the notification") {
            runTest {
                val broadcaster = RegistrationBroadcaster()
                val sub1 = async { broadcaster.subscribe("u1").first() }
                val sub2 = async { broadcaster.subscribe("u1").first() }
                advanceUntilIdle()

                broadcaster.notify("u1", RegistrationDecision.Approved)

                sub1.await() shouldBe RegistrationDecision.Approved
                sub2.await() shouldBe RegistrationDecision.Approved
            }
        }

        test("denied carries the message") {
            runTest {
                val broadcaster = RegistrationBroadcaster()
                val sub = async { broadcaster.subscribe("u1").first() }
                advanceUntilIdle()

                broadcaster.notify("u1", RegistrationDecision.Denied("nope"))

                sub.await() shouldBe RegistrationDecision.Denied("nope")
            }
        }

        test("notify with no subscribers is a no-op (does not throw)") {
            runTest {
                val broadcaster = RegistrationBroadcaster()
                broadcaster.notify("ghost", RegistrationDecision.Approved)

                // A subscriber arriving afterward sees nothing — the drop was a true no-op.
                broadcaster.subscribe("ghost").test {
                    expectNoEvents()
                }
            }
        }
    })
