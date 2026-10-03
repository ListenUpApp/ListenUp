package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import kotlin.math.ceil
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The leaderboard's periods say what they count. They are trailing windows, so the labels are
 * "7 days", "30 days" and "All time", in the same order as iOS and web, and a screen reader hears the
 * full "Last 7 days" rather than a bare number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
// Real text measurement: the fit check below is meaningless with Robolectric's stub fonts.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LeaderboardHeaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val labels = listOf("7 days", "30 days", "All time")

    private fun show(
        width: Int = 360,
        onPeriodSelected: (LeaderboardPeriod) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                LeaderboardHeader(
                    selectedPeriod = LeaderboardPeriod.Week,
                    onPeriodSelected = onPeriodSelected,
                    modifier = Modifier.width(width.dp),
                )
            }
        }
    }

    @Test
    fun offersTrailingPeriodsInOrder() {
        show()
        val lefts =
            labels.map { label ->
                composeRule
                    .onNodeWithText(label)
                    .fetchSemanticsNode()
                    .boundsInRoot.left
            }
        assertEquals(lefts.sorted(), lefts)
    }

    @Test
    fun aScreenReaderHearsTheWholeWindow() {
        show()
        listOf("Last 7 days", "Last 30 days", "All time").forEach { description ->
            composeRule.onNodeWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNode()
        }
    }

    @Test
    fun choosingEachPeriodReportsIt() {
        val chosen = mutableListOf<LeaderboardPeriod>()
        show(onPeriodSelected = { chosen += it })
        labels.forEach { composeRule.onNodeWithText(it).performClick() }
        assertEquals(
            listOf(LeaderboardPeriod.Week, LeaderboardPeriod.Month, LeaderboardPeriod.AllTime),
            chosen,
        )
    }

    /**
     * Inside the Discover card on a 360dp phone (24dp screen margins, 20dp card padding) the row is
     * 272dp wide. Every label must get at least the width it needs — a segmented label clips rather
     * than ellipsizing, so a short box is the only symptom.
     */
    @Test
    fun everyLabelFitsOnANarrowPhone() {
        show(width = 272)
        labels.forEach { label ->
            val node = composeRule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().first()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config
                .getOrNull(SemanticsActions.GetTextLayoutResult)
                ?.action
                ?.invoke(layouts)
            val layout = layouts.single()
            val needed = ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
            assertEquals("$label wraps", 1, layout.lineCount)
            assertTrue("$label gets ${layout.size.width}px of the ${needed}px it needs", layout.size.width >= needed)
        }
    }
}
