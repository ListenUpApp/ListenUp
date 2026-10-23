package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.presentation.player.stepPlaybackSpeed
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.5.1 (PS1–PS5) on Android: the readout, the time at this speed, − slider +, connected presets, reset. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class PlaybackSpeedSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun sheet(
        speed: Float = 1.25f,
        default: Float = 1.0f,
        atSpeed: Long = 116_640_000,
        onSpeedChange: (Float) -> Unit = {},
        onStep: (Boolean) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                PlaybackSpeedSheet(
                    currentSpeed = speed,
                    defaultSpeed = default,
                    bookRemainingAtSpeedMs = { atSpeed },
                    onSpeedChange = onSpeedChange,
                    onStep = onStep,
                    onResetToDefault = {},
                    onDismiss = onDismiss,
                )
            }
        }
    }

    private fun radio(label: String) =
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    fun `the readout, the time at this speed, and the footnote`() {
        sheet(speed = 1.25f, atSpeed = 116_640_000)
        composeRule.onNode(hasText("1.25×") and SemanticsMatcher.keyNotDefined(SemanticsProperties.Role)).assertExists()
        composeRule.onNodeWithText("32h 24m left at this speed").assertExists()
        composeRule.onNodeWithText("Applies to this book only.").assertExists()
    }

    @Test
    fun `minus steps 0_05 through the shared rule, ten presses land exactly`() {
        var speed = 1.5f
        sheet(speed = speed, onStep = { up -> speed = stepPlaybackSpeed(speed, up) })
        repeat(10) { composeRule.onNodeWithContentDescription("Slower by 0.05").performClick() }
        speed shouldBe 1.0f
    }

    @Test
    fun `plus steps up, thirty presses land exactly`() {
        var speed = 1.0f
        sheet(speed = speed, onStep = { up -> speed = stepPlaybackSpeed(speed, up) })
        repeat(30) { composeRule.onNodeWithContentDescription("Faster by 0.05").performClick() }
        speed shouldBe 2.5f
    }

    @Test
    fun `the presets are one connected radio group, the current rung selected`() {
        sheet(speed = 1.25f)
        composeRule.onNodeWithContentDescription("Speed presets").assertExists()
        composeRule.onNode(radio("1.25×")).assertIsOn()
        composeRule.onNode(radio("1.5×")).assertIsOff()
    }

    @Test
    fun `a rate between rungs selects no preset`() {
        sheet(speed = 1.3f)
        composeRule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and isOn())
            .assertCountEquals(0)
    }

    @Test
    fun `reset names the listener's own default`() {
        sheet(speed = 1.25f, default = 1.0f)
        composeRule.onNodeWithText("Reset to 1.0× (your default)").assertExists()
    }

    @Test
    fun `reset is absent at the default`() {
        sheet(speed = 1.0f, default = 1.0f)
        composeRule.onNodeWithText("Reset to 1.0× (your default)").assertDoesNotExist()
    }

    @Test
    fun `choosing a preset reports it and keeps the sheet open`() {
        var dismissed = false
        var chosen: Float? = null
        sheet(speed = 1.0f, onSpeedChange = { chosen = it }, onDismiss = { dismissed = true })
        composeRule.onNode(radio("1.5×")).performClick()
        chosen shouldBe 1.5f
        dismissed shouldBe false
    }

    @Test
    fun `the slider speaks a speed`() {
        sheet(speed = 1.25f)
        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1.25 times"))
    }
}
