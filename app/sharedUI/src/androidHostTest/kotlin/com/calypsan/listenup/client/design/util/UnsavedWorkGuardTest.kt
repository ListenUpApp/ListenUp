package com.calypsan.listenup.client.design.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [rememberUnsavedWorkGuard] is the one place an edit screen asks before it discards a draft: it
 * raises the hold an outside navigation respects, and puts the shared "Unsaved changes" question
 * between a leave request and the leaving.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class UnsavedWorkGuardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val holds = UnsavedWorkHolds()
    private var left = 0
    private var dirty by mutableStateOf(true)

    private fun render() {
        composeRule.setContent {
            CompositionLocalProvider(LocalUnsavedWorkHolds provides holds) {
                MaterialTheme {
                    val requestLeave = rememberUnsavedWorkGuard(dirty = dirty, onLeave = { left++ })
                    TextButton(onClick = requestLeave) { Text("Cancel") }
                }
            }
        }
    }

    @Test
    fun aDirtyScreenRaisesTheHold() {
        render()
        composeRule.waitForIdle()

        holds.isHeld shouldBe true
    }

    @Test
    fun aCleanScreenRaisesNoHold() {
        dirty = false
        render()
        composeRule.waitForIdle()

        holds.isHeld shouldBe false
    }

    @Test
    fun leavingACleanScreenIsImmediate() {
        dirty = false
        render()

        composeRule.onNodeWithText("Cancel").performClick()

        left shouldBe 1
        composeRule.onNodeWithText("Unsaved changes").assertDoesNotExist()
    }

    @Test
    fun leavingADirtyScreenAsksFirstAndKeepEditingStays() {
        render()

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Unsaved changes").assertIsDisplayed()
        left shouldBe 0

        composeRule.onNodeWithText("Keep editing").performClick()

        left shouldBe 0
        composeRule.onNodeWithText("Unsaved changes").assertDoesNotExist()
        holds.isHeld shouldBe true
    }

    @Test
    fun discardingLeaves() {
        render()

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("Discard").performClick()

        left shouldBe 1
    }
}
