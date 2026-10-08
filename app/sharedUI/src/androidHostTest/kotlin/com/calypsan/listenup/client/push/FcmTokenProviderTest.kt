package com.calypsan.listenup.client.push

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for [FcmTokenProvider] — what the push registrar sees of FCM. The registration request
 * stands in for `FirebaseMessaging.register()`; the real one can't run off-device.
 */
class FcmTokenProviderTest :
    FunSpec({

        test("currentToken is the installation ID FCM registers this device under") {
            runTest {
                val handoff = FcmRegistrationHandoff(callbackTimeout = 10.seconds)
                val provider = FcmTokenProvider(handoff) { handoff.offer("fid-1") }

                provider.currentToken() shouldBe "fid-1"
            }
        }

        test("currentToken is null when FCM is unavailable, so push stays off and nothing crashes") {
            runTest {
                val handoff = FcmRegistrationHandoff(callbackTimeout = 10.seconds)
                val provider = FcmTokenProvider(handoff) { throw IllegalStateException("Firebase not initialized") }

                provider.currentToken().shouldBeNull()
            }
        }

        test("currentToken rethrows cancellation instead of reporting no token") {
            runTest {
                val handoff = FcmRegistrationHandoff(callbackTimeout = 10.seconds)
                val provider = FcmTokenProvider(handoff) { throw CancellationException("scope cancelled") }

                shouldThrow<CancellationException> { provider.currentToken() }
            }
        }
    })
