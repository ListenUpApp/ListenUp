package com.calypsan.listenup.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [shouldHandleLaunchIntent] — a recreated activity must not replay the intent that
 * first started it (rotation would otherwise re-resume playback or re-push a deep link).
 */
class LaunchIntentPolicyTest :
    FunSpec({
        test("a fresh launch handles the launch intent") {
            shouldHandleLaunchIntent(isRestoredInstance = false, launchedFromHistory = false) shouldBe true
        }

        test("a recreation (rotation, or restore after process death) does not replay it") {
            shouldHandleLaunchIntent(isRestoredInstance = true, launchedFromHistory = false) shouldBe false
        }

        test("a relaunch from Recents does not replay it") {
            shouldHandleLaunchIntent(isRestoredInstance = false, launchedFromHistory = true) shouldBe false
        }

        test("a restored instance relaunched from history does not replay it") {
            shouldHandleLaunchIntent(isRestoredInstance = true, launchedFromHistory = true) shouldBe false
        }
    })
