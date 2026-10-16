package com.calypsan.listenup.client.features.discover

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** On an expanded window (≥ 840dp) Discover uses the width: grids, not rails, and the community side by side. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w1280dp-h3000dp")
class DiscoverTabletLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun render(state: DiscoverState) {
        composeRule.setContent {
            WithAvatarDependencies {
                DiscoverLayout(state, noActions(), { it() }, PaddingValues(), false, emptySet())
            }
        }
    }

    @Test
    fun leaderboardAndActivitySitSideBySide() {
        render(discoverState(activity = ActivityFeedUiState.Ready(listOf(activityRow("a", "finished_book")))))
        val board = composeRule.onNode(hasText("Leaderboard") and isHeading()).getUnclippedBoundsInRoot()
        val feed = composeRule.onNode(hasText("Activity") and isHeading()).getUnclippedBoundsInRoot()
        assertTrue("the feed (${feed.left}) starts right of the board (${board.right})", feed.left > board.right)
        assertEquals(board.top.value, feed.top.value, 40f)
    }

    @Test
    fun listenersFillTheWidthAsAGridNotARail() {
        val nine = (1..9).map { listener("u$it", "Book $it", isLive = it <= 2) }
        render(discoverState(listening = CurrentlyListeningUiState.Ready(nine, null, false)))
        // Seven 150dp+ cells fit across ~1230dp: every listener is drawn, wrapping onto a second row
        // (a rail would leave the last ones off screen, uncomposed).
        val tops = (1..9).map { composeRule.onNodeWithText("Name u$it", useUnmergedTree = true).getUnclippedBoundsInRoot().top }
        assertEquals(2, tops.distinct().size)
    }

    @Test
    fun shelfOwnersSitSideBySide() {
        val owners =
            listOf(
                shelfOwner("maya", "Maya Chen", shelf("sh1", "Space")),
                shelfOwner("ellie", "Ellie Ward", shelf("sh2", "Cosy")),
            )
        render(discoverState(shelves = DiscoverShelvesUiState.Ready(owners)))
        val first = composeRule.onNodeWithText("Maya Chen", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithText("Ellie Ward", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the second owner (${second.left}) sits right of the first (${first.right})", second.left > first.right)
        assertEquals(first.top.value, second.top.value, 1f)
    }
}
