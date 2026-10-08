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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
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
 * Pins [SettingToggleRow]'s TalkBack contract: the whole row is ONE switch, named by its title,
 * announcing On/Off, and a tap anywhere on the row flips it. Before this primitive every settings
 * switch read "On, switch" with no name, and the label beside it was not a tap target.
 */
@RunWith(RobolectricTestRunner::class)
class SettingToggleRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the row is one switch named by its title`() {
        composeRule.setContent {
            MaterialTheme {
                SettingToggleRow(
                    title = TITLE,
                    subtitle = SUBTITLE,
                    checked = false,
                    onCheckedChange = {},
                )
            }
        }

        composeRule.onAllNodes(isToggleable()).assertCountEquals(1)
        composeRule
            .onNode(isToggleable())
            .assert(hasText(TITLE))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
    }

    @Test
    fun `tapping the row toggles the switch`() {
        var checked by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                SettingToggleRow(
                    title = TITLE,
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
            }
        }

        composeRule.onNodeWithText(TITLE).performClick()

        checked shouldBe true
        composeRule.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun `a toggle is felt`() {
        val haptics = RecordingHaptics()
        composeRule.setContent {
            CompositionLocalProvider(LocalHaptics provides haptics) {
                MaterialTheme {
                    SettingToggleRow(title = TITLE, checked = false, onCheckedChange = {})
                }
            }
        }

        composeRule.onNodeWithText(TITLE).performClick()

        haptics.toggles shouldBe listOf(true)
    }

    @Test
    fun `a disabled row cannot be toggled`() {
        var changes = 0
        composeRule.setContent {
            MaterialTheme {
                SettingToggleRow(
                    title = TITLE,
                    checked = true,
                    enabled = false,
                    onCheckedChange = { changes++ },
                )
            }
        }

        composeRule.onNode(isToggleable()).assertIsNotEnabled().assertIsOn()
        composeRule.onNodeWithText(TITLE).performClick()
        changes shouldBe 0
    }

    private class RecordingHaptics : Haptics {
        val toggles = mutableListOf<Boolean>()

        override fun selectionTick() = Unit

        override fun press() = Unit

        override fun toggle(on: Boolean) {
            toggles += on
        }

        override fun longPress() = Unit

        override fun thresholdActivate() = Unit

        override fun commit() = Unit
    }

    private companion object {
        const val TITLE = "Wi-Fi only downloads"
        const val SUBTITLE = "Downloads wait for Wi-Fi"
    }
}
