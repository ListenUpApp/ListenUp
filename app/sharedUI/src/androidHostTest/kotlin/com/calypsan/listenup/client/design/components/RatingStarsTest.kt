package com.calypsan.listenup.client.design.components

import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `the arrows step one half at a time, and Home and End jump to one and five stars`() {
        var rating by mutableIntStateOf(6)
        composeRule.setContent {
            RatingStars(halfStars = rating, onHalfStarsChange = { rating = it }, modifier = Modifier.testTag(TAG))
        }
        val stars = composeRule.onNodeWithTag(TAG)
        stars.requestFocus()
        stars.assertIsFocused()

        stars.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.runOnIdle { assertEquals(7, rating) }
        stars.performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.runOnIdle { assertEquals(8, rating) }
        stars.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.runOnIdle { assertEquals(7, rating) }
        stars.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.runOnIdle { assertEquals(6, rating) }
        stars.performKeyInput { pressKey(Key.MoveEnd) }
        composeRule.runOnIdle { assertEquals(10, rating) }
        stars.performKeyInput { pressKey(Key.MoveHome) }
        composeRule.runOnIdle { assertEquals(2, rating) }
    }

    @Test
    fun `the first arrow on an unrated input lands on one star`() {
        var chosen: Int? = null
        composeRule.setContent {
            RatingStars(halfStars = 0, onHalfStarsChange = { chosen = it }, modifier = Modifier.testTag(TAG))
        }
        val stars = composeRule.onNodeWithTag(TAG)
        stars.requestFocus()

        stars.performKeyInput { pressKey(Key.DirectionRight) }

        composeRule.runOnIdle { assertEquals(2, chosen) }
    }

    @Test
    fun `read-only stars take no keyboard focus`() {
        composeRule.setContent {
            RatingStars(halfStars = 6, modifier = Modifier.testTag(TAG))
        }

        composeRule
            .onNodeWithTag(TAG)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
    }

    @Test
    fun `a tap commits the rating it sets, once`() {
        val commits = mutableListOf<Int>()
        composeRule.setContent {
            RatingStars(
                halfStars = 0,
                onHalfStarsChange = {},
                onHalfStarsCommit = { commits += it },
                modifier = Modifier.testTag(TAG),
            )
        }

        composeRule.onNodeWithTag(TAG).performTouchInput { click(Offset(width * 0.75f, height / 2f)) }

        assertEquals(listOf(8), commits)
    }

    @Test
    fun `a drag previews every half it crosses and commits only where it lets go`() {
        val changes = mutableListOf<Int>()
        val commits = mutableListOf<Int>()
        composeRule.setContent {
            var stars by remember { mutableIntStateOf(0) }
            RatingStars(
                halfStars = stars,
                onHalfStarsChange = {
                    stars = it
                    changes += it
                },
                onHalfStarsCommit = { commits += it },
                modifier = Modifier.testTag(TAG),
            )
        }

        composeRule.onNodeWithTag(TAG).performTouchInput {
            down(Offset(width * 0.15f, height / 2f))
            moveTo(Offset(width * 0.45f, height / 2f))
            moveTo(Offset(width * 0.95f, height / 2f))
            up()
        }

        assertEquals(listOf(10), commits)
        assertTrue("a drag should preview more than one value, got $changes", changes.size > 1)
    }

    @Test
    fun `a key step commits, because a keyboard has no lift`() {
        val commits = mutableListOf<Int>()
        composeRule.setContent {
            RatingStars(
                halfStars = 6,
                onHalfStarsChange = {},
                onHalfStarsCommit = { commits += it },
                modifier = Modifier.testTag(TAG),
            )
        }

        composeRule.onNodeWithTag(TAG).requestFocus().performKeyInput { pressKey(Key.DirectionRight) }

        assertEquals(listOf(7), commits)
    }

    @Test
    fun `a TalkBack adjustment commits`() {
        val commits = mutableListOf<Int>()
        composeRule.setContent {
            RatingStars(
                halfStars = 6,
                onHalfStarsChange = {},
                onHalfStarsCommit = { commits += it },
                modifier = Modifier.testTag(TAG),
            )
        }

        composeRule.onNodeWithTag(TAG).performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }

        assertEquals(listOf(8), commits)
    }

    private companion object {
        const val TAG = "rating_stars"
    }
}
