package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.design.icons.ListenUpIcons
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class StatTileTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersValueAndLabel() {
        composeRule.setContent {
            MaterialTheme {
                StatTile(
                    value = "184",
                    label = "Sessions written",
                    icon = ListenUpIcons.GraphicEqFilled,
                    colors = StatTileTone.primary(),
                )
            }
        }
        composeRule.onNodeWithText("184").assertIsDisplayed()
        composeRule.onNodeWithText("Sessions written").assertIsDisplayed()
    }

    @Test
    fun tonesAreDistinctInstances() {
        composeRule.setContent {
            MaterialTheme {
                val primary = StatTileTone.primary()
                val tertiary = StatTileTone.tertiary()
                val neutral = StatTileTone.neutral()
                val allDiffer =
                    primary.container != tertiary.container &&
                        tertiary.container != neutral.container
                androidx.compose.material3.Text(if (allDiffer) "TONES_OK" else "TONES_BAD")
            }
        }
        composeRule.onNodeWithText("TONES_OK").assertIsDisplayed()
    }
}
