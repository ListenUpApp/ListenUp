package com.calypsan.listenup.client.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec §3.8: a loading section keeps its place with a skeleton the same shape as what it stands in
 * for, and the skeleton is silent to TalkBack (the heading above it already says what is coming).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h900dp")
class SectionSkeletonTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a rail skeleton is hidden from TalkBack and as tall as the cards it stands in for`() {
        composeRule.setContent {
            MaterialTheme {
                RailSkeleton(cardWidth = 140.dp, captionLines = 2, modifier = Modifier.testTag("rail"))
            }
        }

        composeRule.onNodeWithTag("rail").assertHeightIsAtLeast(140.dp + 2 * 16.dp)
        composeRule.onAllNodes(hasContentDescription("", substring = true)).assertCountEquals(0)
        composeRule.onAllNodes(hasText("", substring = true)).assertCountEquals(0)
        assertEquals(
            0,
            composeRule
                .onNodeWithTag("rail")
                .fetchSemanticsNode()
                .children.size,
        )
    }

    @Test
    fun `a rows skeleton is as tall as the rows it stands in for, and silent`() {
        composeRule.setContent {
            MaterialTheme {
                RowsSkeleton(rows = 3, modifier = Modifier.testTag("rows"))
            }
        }

        composeRule.onNodeWithTag("rows").assertHeightIsAtLeast(3 * 64.dp)
        composeRule.onAllNodesWithTag("rows").assertCountEquals(1)
        assertEquals(
            0,
            composeRule
                .onNodeWithTag("rows")
                .fetchSemanticsNode()
                .children.size,
        )
    }
}
