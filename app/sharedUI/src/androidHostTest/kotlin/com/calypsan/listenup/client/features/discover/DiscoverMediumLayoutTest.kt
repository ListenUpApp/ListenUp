package com.calypsan.listenup.client.features.discover

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a 7" tablet (a medium window, 600dp, less the navigation rail) two community columns would each be
 * too narrow for a name or a sentence, so the leaderboard and the activity stack as they do on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w600dp-h3000dp")
class DiscoverMediumLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    @Test
    fun leaderboardAndActivityStackWhenTwoColumnsWouldBeCramped() {
        composeRule.setContent {
            WithAvatarDependencies {
                DiscoverLayout(
                    discoverState(activity = ActivityFeedUiState.Ready(listOf(activityRow("a", "finished_book")))),
                    noActions(),
                    { it() },
                    PaddingValues(),
                    false,
                    emptySet(),
                )
            }
        }
        val board = composeRule.onNode(hasText("Leaderboard") and isHeading()).getUnclippedBoundsInRoot()
        val feed = composeRule.onNode(hasText("Activity") and isHeading()).getUnclippedBoundsInRoot()
        assertTrue("the feed (${feed.top}) starts below the board (${board.bottom})", feed.top > board.bottom)
    }
}
