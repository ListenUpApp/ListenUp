package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.design.haptics.Haptics
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The composed-body [ListenUpAlertDialog] every custom dialog now routes through: confirm is felt as
 * a commit, dismiss as a press, and a confirm that is not yet available is truly disabled.
 */
@RunWith(RobolectricTestRunner::class)
class ListenUpAlertDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val haptics = RecordingHaptics()
    private val events = mutableListOf<String>()

    private fun render(confirmEnabled: Boolean = true) {
        composeRule.setContent {
            CompositionLocalProvider(LocalHaptics provides haptics) {
                MaterialTheme {
                    ListenUpAlertDialog(
                        onDismissRequest = { events += "request" },
                        title = "Rename chapter",
                        confirmText = "Save",
                        onConfirm = { events += "confirm" },
                        dismissText = "Cancel",
                        onDismiss = { events += "dismiss" },
                        confirmEnabled = confirmEnabled,
                    ) {
                        Text("Body")
                    }
                }
            }
        }
    }

    @Test
    fun `the body is composed and confirm commits`() {
        render()

        composeRule.onNodeWithText("Body").assertIsDisplayed()
        composeRule.onNodeWithText("Save").performClick()

        events shouldBe listOf("confirm")
        haptics.felt shouldBe listOf("commit")
    }

    @Test
    fun `dismiss presses`() {
        render()

        composeRule.onNodeWithText("Cancel").performClick()

        events shouldBe listOf("dismiss")
        haptics.felt shouldBe listOf("press")
    }

    @Test
    fun `an unavailable confirm is disabled and silent`() {
        render(confirmEnabled = false)

        composeRule.onNodeWithText("Save").assertIsNotEnabled().performClick()

        events shouldBe emptyList()
        haptics.felt shouldBe emptyList()
    }

    private class RecordingHaptics : Haptics {
        val felt = mutableListOf<String>()

        override fun selectionTick() = Unit

        override fun press() {
            felt += "press"
        }

        override fun toggle(on: Boolean) = Unit

        override fun longPress() = Unit

        override fun thresholdActivate() = Unit

        override fun commit() {
            felt += "commit"
        }
    }
}
