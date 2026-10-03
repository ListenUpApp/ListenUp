package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil

/**
 * The leaderboard's period selector: a Material 3 Expressive connected button group on its own row
 * at the screen margins, offering the four trailing windows in the same order as iOS and web —
 * "7 days", "30 days", "12 months", "All time" — each button its label's width plus an even share of
 * what's left, wrapping to two pairs when the text is too large for one row. A screen reader hears a
 * group of radio buttons named for the full window ("Last 7 days").
 *
 * Widths are the row the header gets at the 24dp screen margins: 312dp on a 360dp phone, 364dp on a
 * 412dp one.
 */
@RunWith(RobolectricTestRunner::class)
// A wide-enough screen that every width under test is the width the header actually gets.
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w800dp-h1600dp-mdpi")
// Real text measurement: the fit checks are meaningless with Robolectric's stub fonts.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LeaderboardHeaderTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val labels = listOf("7 days", "30 days", "12 months", "All time")

    private fun show(
        width: Int = 312,
        fontScale: Float = 1f,
        selected: LeaderboardPeriod = LeaderboardPeriod.Month,
        onPeriodSelected: (LeaderboardPeriod) -> Unit = {},
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    LeaderboardHeader(
                        selectedPeriod = selected,
                        onPeriodSelected = onPeriodSelected,
                        modifier = Modifier.width(width.dp),
                    )
                }
            }
        }
    }

    private fun labelNode(label: String): SemanticsNode =
        composeRule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().first()

    private fun assertLabelGetsItsFullWidth(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        labelNode(label)
            .config
            .getOrNull(SemanticsActions.GetTextLayoutResult)
            ?.action
            ?.invoke(layouts)
        val layout = layouts.single()
        val needed = ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
        assertEquals("$label wraps", 1, layout.lineCount)
        assertTrue("$label gets ${layout.size.width}px of the ${needed}px it needs", layout.size.width >= needed)
    }

    private fun buttonNode(description: String): SemanticsNode = composeRule.onNodeWithContentDescription(description).fetchSemanticsNode()

    @Test
    fun offersFourTrailingPeriodsInOneRowInOrder() {
        show()
        val bounds = labels.map { labelNode(it).boundsInRoot }
        assertEquals(bounds.map { it.left }.sorted(), bounds.map { it.left })
        assertEquals("one row", 1, bounds.map { it.top }.distinct().size)
    }

    @Test
    fun everyLabelGetsItsFullWidthOnA360dpPhone() {
        show(width = 312)
        labels.forEach(::assertLabelGetsItsFullWidth)
    }

    @Test
    fun everyLabelGetsItsFullWidthOnA412dpPhone() {
        show(width = 364)
        labels.forEach(::assertLabelGetsItsFullWidth)
    }

    @Test
    fun onAWideWindowTheButtonsAreEqualAndTheRowStopsAt480dp() {
        show(width = 700)
        val buttons = listOf("Last 7 days", "Last 30 days", "Last 12 months", "All time").map { buttonNode(it).boundsInRoot }
        val widths = buttons.map { it.width }
        widths.forEach { assertEquals("equal widths $widths", widths.first(), it, 1f) }
        assertTrue("row is ${buttons.last().right - buttons.first().left}dp", buttons.last().right - buttons.first().left <= 480f)
    }

    @Test
    fun atDoubleTextSizeItWrapsToTwoPairsWithEveryLabelWhole() {
        show(width = 312, fontScale = 2f)
        val tops = labels.map { labelNode(it).boundsInRoot.top }
        assertEquals("7 days and 30 days share a row", tops[0], tops[1])
        assertEquals("12 months and All time share a row", tops[2], tops[3])
        assertTrue("the second pair sits below the first", tops[2] > tops[0])
        labels.forEach(::assertLabelGetsItsFullWidth)
    }

    @Test
    fun aScreenReaderHearsARadioGroupNamedForEachWindow() {
        show(selected = LeaderboardPeriod.Month)
        val group = composeRule.onNodeWithContentDescription("Leaderboard period").fetchSemanticsNode()
        assertNotNull("the group is a selectable group", group.config.getOrNull(SemanticsProperties.SelectableGroup))

        listOf("Last 7 days", "Last 30 days", "Last 12 months", "All time").forEach { description ->
            val node = buttonNode(description)
            assertEquals("$description is a radio button", Role.RadioButton, node.config.getOrNull(SemanticsProperties.Role))
            val on = node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
            assertEquals("$description checked", description == "Last 30 days", on)
        }
    }

    @Test
    fun thePeriodsAreExpressiveToggleButtons() {
        // An M3 Expressive ToggleButton is toggleable (it carries a ToggleableState); the old
        // SegmentedButton was a selectable segment and carried a Selected state instead.
        show()
        val node = buttonNode("Last 7 days")
        assertNotNull(node.config.getOrNull(SemanticsProperties.ToggleableState))
        assertNull(node.config.getOrNull(SemanticsProperties.Selected))
        // 40dp buttons (ToggleButtonDefaults.MinHeight).
        assertEquals(40f, node.boundsInRoot.height, 0.5f)
    }

    @Test
    fun choosingEachPeriodReportsIt() {
        val chosen = mutableListOf<LeaderboardPeriod>()
        show(onPeriodSelected = { chosen += it })
        labels.forEach { composeRule.onNodeWithText(it).performClick() }
        assertEquals(
            listOf(LeaderboardPeriod.Week, LeaderboardPeriod.Month, LeaderboardPeriod.Year, LeaderboardPeriod.AllTime),
            chosen,
        )
    }
}
