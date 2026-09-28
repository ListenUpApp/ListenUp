package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Pins [SaveAction]'s contract: Save is a real top-bar button that is truly disabled until there is
 * something to save. It replaced a Save FAB that only recoloured when "disabled", so TalkBack
 * announced an actionable control that did nothing.
 */
@RunWith(RobolectricTestRunner::class)
class SaveActionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `with no changes Save is disabled and a tap does nothing`() {
        var saves = 0
        composeRule.setContent {
            MaterialTheme { SaveAction(onClick = { saves++ }, enabled = false) }
        }

        composeRule
            .onNodeWithText(SAVE)
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()

        saves shouldBe 0
    }

    @Test
    fun `a change enables Save and a tap saves once, with a press felt`() {
        var hasChanges by mutableStateOf(false)
        var saves = 0
        val haptics = CountingHaptics()
        composeRule.setContent {
            CompositionLocalProvider(LocalHaptics provides haptics) {
                MaterialTheme { SaveAction(onClick = { saves++ }, enabled = hasChanges) }
            }
        }

        composeRule.onNodeWithText(SAVE).assertIsNotEnabled()
        hasChanges = true
        composeRule.onNodeWithText(SAVE).assertIsEnabled().performClick()

        saves shouldBe 1
        haptics.presses shouldBe 1
    }

    @Test
    fun `while saving the action names the work and cannot be tapped again`() {
        var saves = 0
        composeRule.setContent {
            MaterialTheme { SaveAction(onClick = { saves++ }, enabled = true, isBusy = true) }
        }

        composeRule.onNodeWithText(SAVING).assertIsNotEnabled().performClick()

        saves shouldBe 0
    }

    private class CountingHaptics : Haptics {
        var presses = 0

        override fun selectionTick() = Unit

        override fun press() {
            presses++
        }

        override fun toggle(on: Boolean) = Unit

        override fun longPress() = Unit

        override fun thresholdActivate() = Unit

        override fun commit() = Unit
    }

    private companion object {
        const val SAVE = "Save"
        const val SAVING = "Saving…"
    }
}
