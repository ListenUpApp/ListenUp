package com.calypsan.listenup.client.push

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PushActionReceiverTest :
    FunSpec({
        test("on Android 11 a locked phone cannot decide a registration") {
            isRegistrationDecisionAllowed(sdkInt = 30, isDeviceLocked = { true }) shouldBe false
        }

        test("on Android 11 an unlocked phone decides normally") {
            isRegistrationDecisionAllowed(sdkInt = 30, isDeviceLocked = { false }) shouldBe true
        }

        test("from Android 12 the OS has already demanded the unlock, so the lock state is not read") {
            isRegistrationDecisionAllowed(sdkInt = 31, isDeviceLocked = { error("must not be read") }) shouldBe true
        }
    })
