package com.calypsan.listenup.client.design.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The design system's star rating: what it says to TalkBack, and what a tap sets. */
@RunWith(RobolectricTestRunner::class)
class RatingStarsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `read-only stars announce the rating out of five`() {
        composeRule.setContent {
            RatingStars(halfStars = 7, modifier = Modifier.testTag(TAG))
        }

        composeRule.onNodeWithTag(TAG).assertContentDescriptionEquals("3.5 out of 5 stars")
    }

    @Test
    fun `tapping the right half of the fourth star sets four stars`() {
        var chosen: Int? = null
        composeRule.setContent {
            RatingStars(
                halfStars = 0,
                onHalfStarsChange = { chosen = it },
                modifier = Modifier.testTag(TAG),
            )
        }

        // Five equal stars: the fourth spans 60%..80% of the width, its right half 70%..80%.
        composeRule.onNodeWithTag(TAG).performTouchInput { click(Offset(width * 0.75f, height / 2f)) }

        assertEquals(8, chosen)
    }

    private companion object {
        const val TAG = "rating_stars"
    }
}
