package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §5.5.3 (PB1–PB5) on Android: the readout, the levels as one connected choice, the note, reset; it stays open. */
@Config(qualifiers = "w412dp-h915dp")
@RunWith(RobolectricTestRunner::class)
class VolumeBoostSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val chosen = mutableListOf<Float>()
    private var dismissed = 0

    private fun sheet(
        boost: Float = 6f,
        default: Float = 0f,
    ) {
        composeRule.setContent {
            MaterialTheme {
                VolumeBoostSheet(
                    currentBoostDb = boost,
                    defaultBoostDb = default,
                    onBoostChange = { chosen += it },
                    onResetToDefault = {},
                    onDismiss = { dismissed++ },
                )
            }
        }
    }

    private fun level(label: String) =
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test
    fun `the readout says the level in decibels`() {
        sheet(boost = 6f)
        composeRule.onNode(hasText("+6 dB") and SemanticsMatcher.keyNotDefined(SemanticsProperties.Role)).assertExists()
    }

    @Test
    fun `the five levels are one connected choice, the current one on`() {
        sheet(boost = 6f)
        composeRule.onNodeWithContentDescription("Boost level").assertExists()
        listOf("Off" to "Off", "+3" to "+3 dB", "+9" to "+9 dB", "+12" to "+12 dB").forEach { (label, spoken) ->
            composeRule.onNode(level(label) and hasContentDescription(spoken)).assertIsOff()
        }
        composeRule.onNode(level("+6") and hasContentDescription("+6 dB")).assertIsOn()
    }

    @Test
    fun `picking a level reports it and keeps the sheet open`() {
        sheet(boost = 6f)
        composeRule.onNode(level("+9")).performClick()
        chosen shouldBe listOf(9f)
        dismissed shouldBe 0
    }

    @Test
    fun `reset names the default when off it`() {
        sheet(boost = 6f, default = 0f)
        composeRule.onNodeWithText("Reset to Off (your default)").assertExists()
    }

    @Test
    fun `reset is absent at the default`() {
        sheet(boost = 0f, default = 0f)
        composeRule.onNodeWithText("Reset to Off (your default)").assertDoesNotExist()
    }

    @Test
    fun `the note says what boost is for`() {
        sheet()
        composeRule.onNodeWithText("Louder for quiet recordings. Applies to this book only.").assertExists()
    }
}
