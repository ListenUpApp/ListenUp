package com.calypsan.listenup.client.features.bookedit.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.features.contributoredit.components.ContributorStudioCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The edit screens are long stacks of titled cards; TalkBack's heading navigation is how a
 * listener jumps from "Classification" to "Media" without hearing every field in between.
 */
@RunWith(RobolectricTestRunner::class)
class StudioCardHeadingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a book edit card title is a heading`() {
        composeRule.setContent {
            MaterialTheme {
                StudioCard(title = "Classification") {}
            }
        }

        composeRule.onNodeWithText("Classification").assert(isHeading())
    }

    @Test
    fun `a contributor edit card title is a heading`() {
        composeRule.setContent {
            MaterialTheme {
                ContributorStudioCard(title = "Biography") {}
            }
        }

        composeRule.onNodeWithText("Biography").assert(isHeading())
    }
}
