package com.calypsan.listenup.client.features.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Search results come in groups (Books, People, Series, …); each group's title is a heading so
 * TalkBack can jump straight to the people instead of reading every book first.
 */
@RunWith(RobolectricTestRunner::class)
class GroupHeaderHeadingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a result group title is a heading`() {
        composeRule.setContent {
            MaterialTheme {
                GroupHeader(
                    title = "People",
                    count = 3,
                    badgeContainer = Color.Gray,
                    badgeContent = Color.Black,
                )
            }
        }

        composeRule.onNodeWithText("People").assert(isHeading())
    }

    @Test
    fun `a group's See all says which group it opens`() {
        composeRule.setContent {
            MaterialTheme {
                GroupHeader(
                    title = "People",
                    count = 9,
                    badgeContainer = Color.Gray,
                    badgeContent = Color.Black,
                    onSeeAll = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("See all People").assertExists()
    }
}
