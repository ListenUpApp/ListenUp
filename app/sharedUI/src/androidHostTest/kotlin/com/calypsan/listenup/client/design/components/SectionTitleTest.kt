package com.calypsan.listenup.client.design.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the heading semantics on the app's title primitives, so TalkBack's heading navigation can
 * jump between sections (Home, Book detail, Settings) instead of reading every row in between.
 */
@RunWith(RobolectricTestRunner::class)
class SectionTitleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a section title is a heading`() {
        composeRule.setContent {
            MaterialTheme {
                SectionTitle(title = "Continue Listening", onSeeAll = {})
            }
        }

        composeRule.onNodeWithText("Continue Listening").assert(isHeading())
    }

    @Test
    fun `a colour-block hero title is a heading`() {
        composeRule.setContent {
            MaterialTheme {
                ColorBlockHero(title = "Administration", badgeIcon = Icons.Default.Settings, onBack = {})
            }
        }

        composeRule.onNodeWithText("Administration").assert(isHeading())
    }

    @Test
    fun `a detail hero title is a heading`() {
        composeRule.setContent {
            MaterialTheme {
                DetailHero(
                    collapseFraction = { 0f },
                    collapsing = false,
                    gradientColors = listOf(Color.DarkGray, Color.Black),
                    navigation = {},
                    title = "The Way of Kings",
                    backdropMedia = {},
                )
            }
        }

        composeRule.onNodeWithText("The Way of Kings").assert(isHeading())
    }

    @Test
    fun `a top app bar title is a heading`() {
        composeRule.setContent {
            MaterialTheme {
                ListenUpTopAppBar(title = "Storage")
            }
        }

        composeRule.onNodeWithText("Storage").assert(isHeading())
    }
}
