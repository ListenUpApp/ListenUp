package com.calypsan.listenup.client.design.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
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

    @Test
    fun `the input says Rating once and carries its value as state`() {
        composeRule.setContent {
            RatingStars(halfStars = 7, onHalfStarsChange = {}, modifier = Modifier.testTag(TAG))
        }

        composeRule
            .onNodeWithTag(TAG)
            .assertContentDescriptionEquals("Rating")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "3.5 out of 5 stars"))
    }

    @Test
    fun `an unrated input reads Not rated and reports no progress`() {
        composeRule.setContent {
            RatingStars(halfStars = 0, onHalfStarsChange = {}, modifier = Modifier.testTag(TAG))
        }

        composeRule
            .onNodeWithTag(TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not rated"))
            .assert(
                SemanticsMatcher("reports progress 0") {
                    it.config[SemanticsProperties.ProgressBarRangeInfo].current == 0f
                },
            )
    }

    @Test
    fun `the first TalkBack step up from unrated lands on one star`() {
        var chosen: Int? = null
        composeRule.setContent {
            RatingStars(halfStars = 0, onHalfStarsChange = { chosen = it }, modifier = Modifier.testTag(TAG))
        }

        // TalkBack's increment asks for the next step of the 0..10 range: 1.
        composeRule.onNodeWithTag(TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }

        assertEquals(2, chosen)
    }

    @Test
    fun `the input is at least 48dp tall`() {
        composeRule.setContent {
            RatingStars(halfStars = 0, onHalfStarsChange = {}, starSize = 24.dp, modifier = Modifier.testTag(TAG))
        }

        composeRule.onNodeWithTag(TAG).assertHeightIsAtLeast(48.dp)
    }

    private companion object {
        const val TAG = "rating_stars"
    }
}
