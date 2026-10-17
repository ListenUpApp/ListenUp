package com.calypsan.listenup.client.features.discover.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The leaderboard's period, always visible: four single-select filter chips (Week, Month, Year,
 * All time), in that order, one selected, heard by a screen reader as a radio group. Carries the
 * intent of the retired LeaderboardHeaderTest ("four windows, in order, one selected, a radio group").
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LeaderboardPeriodChipsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fourPeriodsInOrderOneSelectedAsARadioGroup() {
        composeRule.setContent { MaterialTheme { LeaderboardPeriodChips(selected = LeaderboardPeriod.Month, onSelect = {}) } }
        val chips = composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        chips.assertCountEquals(4)
        listOf("Week", "Month", "Year", "All time").forEachIndexed { i, label -> chips[i].assertTextContains(label) }
        chips[1].assertIsSelected()
        listOf(0, 2, 3).forEach { chips[it].assertIsNotSelected() }
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertExists()
    }

    @Test
    fun pressingAPeriodReportsItOnce() {
        val picked = mutableListOf<LeaderboardPeriod>()
        composeRule.setContent { MaterialTheme { LeaderboardPeriodChips(selected = LeaderboardPeriod.Week, onSelect = { picked += it }) } }
        composeRule.onNodeWithText("Year").performClick()
        assertEquals(listOf(LeaderboardPeriod.Year), picked)
    }

    @Test
    fun atTwiceTheFontScaleTheChipsWrapRatherThanClip() {
        // A 312dp row at 2× font: every chip is still fully on screen (FlowRow wraps).
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MaterialTheme { Box(Modifier.width(312.dp)) { LeaderboardPeriodChips(LeaderboardPeriod.Week, {}) } }
            }
        }
        composeRule.onNodeWithText("All time").assertIsDisplayed()
    }
}
