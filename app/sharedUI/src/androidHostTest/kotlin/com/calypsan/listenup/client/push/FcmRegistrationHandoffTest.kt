package com.calypsan.listenup.client.push

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [FcmRegistrationHandoff] — the seam that maps FCM's `register()` + `onRegistered`
 * pair back onto the on-demand [com.calypsan.listenup.client.data.push.PushTokenProvider] contract.
 * Firebase can't run in a host test, so the registration request is a lambda that plays the
 * part of `FirebaseMessaging.register()` and, like the real one, delivers `onRegistered` by
 * calling [FcmRegistrationHandoff.offer].
 */
class FcmRegistrationHandoffTest :
    FunSpec({

        test("register returns the installation ID that onRegistered delivers") {
            runTest {
                val handoff = FcmRegistrationHandoff(callbackTimeout = 10.seconds)

                val claimed = mutableListOf<Boolean>()
                val fid = handoff.register { claimed += handoff.offer("fid-1") }

                fid shouldBe "fid-1"
                // Claimed by the waiting caller, so the service must not also treat it as a rotation.
                claimed shouldBe listOf(true)
            }
        }

        test("an onRegistered nobody is waiting for is unclaimed, so it routes to rotation") {
            val handoff = FcmRegistrationHandoff(callbackTimeout = 10.seconds)

            handoff.offer("rotated-fid") shouldBe false
        }

        test("register returns null when onRegistered never arrives, and the late callback is unclaimed") {
            runTest {
                val handoff = FcmRegistrationHandoff(callbackTimeout = 10.seconds)

                handoff.register { /* registered, but the callback is lost or late */ }.shouldBeNull()

                // The late callback must still reach the server, through the rotation path.
                handoff.offer("late-fid") shouldBe false
            }
        }

        test("a failed registration request propagates and leaves no waiter behind") {
            runTest {
                val handoff = FcmRegistrationHandoff(callbackTimeout = 10.seconds)

                shouldThrow<IllegalStateException> {
                    handoff.register { throw IllegalStateException("no Play services") }
                }

                handoff.offer("fid-after-failure") shouldBe false
            }
        }

        test("cancellation while waiting leaves no waiter behind") {
            runTest {
                val handoff = FcmRegistrationHandoff(callbackTimeout = 10.seconds)

                shouldThrow<CancellationException> {
                    handoff.register { throw CancellationException("caller went away") }
                }

                handoff.offer("fid-after-cancel") shouldBe false
            }
        }
    })
