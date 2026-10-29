package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.features.home.components.HOME_STATS_CHART_TAG
import com.calypsan.listenup.client.presentation.home.HomeSlot
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * K1 (spec §9): a 412×915dp phone, a book loaded, three other books in progress. "Your last 7 days", the week's total,
 * the streak chip and the whole chart sit above the mini player's top edge (915 − 64dp nav − 72dp mini = 779dp) on
 * arrival. Robolectric reports no system bars, so the status bar is a 24dp top inset here, as on a Pixel.
 */
@Config(qualifiers = "w412dp-h915dp")
// Native graphics: legacy text shaping inflates every line, which is not the geometry K1/K2 pin.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class HomeFirstScreenGeometryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the whole week is on the first screen`() {
        composeRule.setContent {
            MaterialTheme {
                HomeBody(
                    state = homeReady(slot = HomeSlot.Absent, everythingInProgress = (1..3).map { readyItem("b$it") }),
                    stats = weekWithStreak(),
                    width = HomeWidth.Compact,
                    actions = HomeActions.None,
                    selection = HomeSelection.None,
                    appHeader = testAppHeader,
                    contentPadding = PaddingValues(top = 24.dp, bottom = 136.dp),
                )
            }
        }
        val miniPlayerTop = 779.dp
        composeRule.onNodeWithText("Your last 7 days").getUnclippedBoundsInRoot().bottom shouldBeLessThanOrEqualTo
            miniPlayerTop
        composeRule.onNodeWithText("5-day streak").getUnclippedBoundsInRoot().bottom shouldBeLessThanOrEqualTo
            miniPlayerTop
        composeRule
            .onNodeWithTag(HOME_STATS_CHART_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .bottom shouldBeLessThanOrEqualTo miniPlayerTop
    }
}
