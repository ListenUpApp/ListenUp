package com.calypsan.listenup.client.design.haptics

import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProvideHapticsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `provider installs a real Haptics and a GatedHapticFeedback`() {
        lateinit var haptics: Haptics
        lateinit var feedbackClassName: String

        composeRule.setContent {
            ProvideHaptics(hapticFeedbackEnabled = true) {
                haptics = LocalHaptics.current
                feedbackClassName = LocalHapticFeedback.current::class.simpleName ?: ""
            }
        }

        composeRule.runOnIdle {
            haptics.shouldBeInstanceOf<HapticFeedbackHaptics>()
            feedbackClassName shouldBe "GatedHapticFeedback"
        }
    }

    @Test
    fun `LocalHaptics defaults to NoOp outside any provider`() {
        lateinit var haptics: Haptics
        composeRule.setContent { haptics = LocalHaptics.current }
        composeRule.runOnIdle { (haptics === NoOpHaptics) shouldBe true }
    }
}
