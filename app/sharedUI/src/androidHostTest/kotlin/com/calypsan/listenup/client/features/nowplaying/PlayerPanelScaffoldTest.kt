package com.calypsan.listenup.client.features.nowplaying

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.nowplaying.components.PlayerPanelScaffold
import com.calypsan.listenup.client.testing.AtFontScale
import com.calypsan.listenup.client.testing.assertTouchTargetAtLeast
import com.calypsan.listenup.client.testing.textLayout
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sheets' one shell (§3.8, §8): it scrolls at large text, its title is Medium, and its close is 48dp. */
@RunWith(RobolectricTestRunner::class)
class PlayerPanelScaffoldTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun panel(lines: Int = 3) {
        composeRule.setContent {
            AtFontScale {
                MaterialTheme {
                    PlayerPanelScaffold(title = "Playback speed", onDismiss = {}) {
                        repeat(lines) { Text("Line $it") }
                    }
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the dialog's close is at least 48dp, drawn and touched`() {
        panel()
        composeRule
            .onNodeWithContentDescription("Close")
            .assertTouchTargetAtLeast()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `at the largest font a tall sheet scrolls to its last line`() {
        panel(lines = 30)
        composeRule.onNodeWithText("Line 29").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `at the largest font a tall dialog scrolls to its last line`() {
        panel(lines = 30)
        composeRule.onNodeWithText("Line 29").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun `the title is Medium, not Bold`() {
        panel()
        composeRule.onNodeWithText("Playback speed").textLayout().layoutInput.style.fontWeight shouldBe
            FontWeight.Medium
    }
}
