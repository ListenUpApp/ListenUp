package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DetailHeroTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersTitleSubtitleAndSlots() {
        composeRule.setContent {
            MaterialTheme {
                DetailHero(
                    collapseFraction = { 0f },
                    collapsing = true,
                    gradientColors = listOf(Color.DarkGray, Color.Black),
                    navigation = { pinnedTitle ->
                        Text("BACK")
                        pinnedTitle()
                    },
                    title = "The Way of Kings",
                    subtitle = "Brandon Sanderson",
                    backdropMedia = { Text("COVER") },
                    belowTitle = { Text("STATS") },
                )
            }
        }
        composeRule.onNodeWithText("BACK").assertIsDisplayed()
        composeRule.onNodeWithText("COVER").assertIsDisplayed()
        composeRule.onNodeWithText("Brandon Sanderson").assertIsDisplayed()
        composeRule.onNodeWithText("STATS").assertIsDisplayed()
    }

    @Test
    fun collapsingModeEmitsBothTitleTreatments() {
        composeRule.setContent {
            MaterialTheme {
                DetailHero(
                    collapseFraction = { 0f },
                    collapsing = true,
                    gradientColors = listOf(Color.DarkGray, Color.Black),
                    navigation = { pinnedTitle -> pinnedTitle() },
                    title = "Collapsing Title",
                    backdropMedia = { Text("COVER") },
                )
            }
        }
        val count = composeRule.onAllNodesWithText("Collapsing Title").fetchSemanticsNodes().size
        assert(count == 2) { "Expected 2 title nodes (expanded + pinned) in collapsing mode, got $count" }
    }

    @Test
    fun staticModeEmitsOnlyExpandedTitle() {
        composeRule.setContent {
            MaterialTheme {
                DetailHero(
                    collapseFraction = { 0f },
                    collapsing = false,
                    gradientColors = listOf(Color.DarkGray, Color.Black),
                    navigation = { },
                    title = "Static Title",
                    backdropMedia = { Text("COVER") },
                )
            }
        }
        val count = composeRule.onAllNodesWithText("Static Title").fetchSemanticsNodes().size
        assert(count == 1) { "Expected exactly 1 title node in static mode, got $count" }
    }

    @Test
    fun cookieScallopShapeIsUsable() {
        composeRule.setContent {
            MaterialTheme {
                val shape = cookieScallopShape()
                Text(if (shape.toString().isNotEmpty()) "SHAPE_OK" else "SHAPE_BAD")
            }
        }
        composeRule.onNodeWithText("SHAPE_OK").assertIsDisplayed()
    }
}
