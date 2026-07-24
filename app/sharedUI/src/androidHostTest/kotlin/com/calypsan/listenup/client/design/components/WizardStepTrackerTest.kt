package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class WizardStepTrackerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val steps = listOf("Upload", "Review", "Apply", "Done")

    @Test
    fun rendersEveryStepLabel() {
        composeRule.setContent {
            MaterialTheme {
                WizardStepTracker(steps = steps, currentStep = 1)
            }
        }
        steps.forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun rendersWithoutLabelsWhenEmpty() {
        composeRule.setContent {
            MaterialTheme {
                WizardStepTracker(steps = emptyList(), currentStep = 0)
            }
        }
        // No crash, nothing to assert beyond composition succeeding.
        composeRule.onNodeWithText("Upload").assertDoesNotExist()
    }
}
