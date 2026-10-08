package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.calypsan.listenup.client.testing.AtFontScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * At the largest font the color-block hero used to stay pinned and take about 270dp of a 767dp screen, leaving
 * Admin a slot to be read through. Now it slides away as the content scrolls; at the default size it stays put.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w345dp-h767dp")
class ColorBlockHeroScrollTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun screen(fontScale: Float) {
        composeRule.setContent {
            AtFontScale(fontScale) {
                MaterialTheme {
                    val heroScroll = rememberHeroScrollBehavior()
                    ListenUpScaffold(
                        modifier = heroScroll?.let { Modifier.nestedScroll(it.nestedScrollConnection) } ?: Modifier,
                        topBar = {
                            ColorBlockHero(
                                title = TITLE,
                                badgeIcon = Icons.Outlined.Shield,
                                onBack = {},
                                scrollBehavior = heroScroll,
                            )
                        },
                    ) { padding ->
                        LazyColumn(contentPadding = padding, modifier = Modifier.testTag(LIST)) {
                            items(ROWS) { Text("Setting $it") }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `at the largest font the hero slides away as the content scrolls`() {
        screen(fontScale = 2f)
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()

        composeRule.onNodeWithTag(LIST).performTouchInput { swipeUp() }

        composeRule.onNodeWithText(TITLE).assertIsNotDisplayed()
    }

    @Test
    fun `at the default font the hero stays put`() {
        screen(fontScale = 1f)

        composeRule.onNodeWithTag(LIST).performTouchInput { swipeUp() }

        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
    }

    private companion object {
        const val TITLE = "Administration"
        const val LIST = "list"
        const val ROWS = 80
    }
}
