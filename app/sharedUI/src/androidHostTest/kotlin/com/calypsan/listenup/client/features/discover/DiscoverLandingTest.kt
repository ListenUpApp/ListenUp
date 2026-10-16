package com.calypsan.listenup.client.features.discover

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.features.shell.LocalShellLandings
import com.calypsan.listenup.client.features.shell.ShellLanding
import com.calypsan.listenup.client.features.shell.ShellLandings
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The stats card's footer link lands Discover on its leaderboard, Week selected, in view (spec §6.4). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w412dp-h600dp")
class DiscoverLandingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    @Test
    fun theLandingSelectsWeekAndBringsTheLeaderboardIntoView() {
        val landings = ShellLandings {}
        val periods = mutableListOf<LeaderboardPeriod>()
        // Enough above the board that, unscrolled, its heading starts below a 600dp window.
        val many = (1..10).map { pick("p$it", "Book $it", "Series", "$it") }
        composeRule.setContent {
            WithAvatarDependencies {
                CompositionLocalProvider(LocalShellLandings provides landings) {
                    MaterialTheme {
                        DiscoverLanding(onSelectPeriod = { periods += it }) { listState ->
                            DiscoverLayout(
                                discoverState(
                                    listening = CurrentlyListeningUiState.Ready(
                                        (1..4).map { listener("u$it", "Book $it", isLive = true) },
                                        null,
                                        false,
                                    ),
                                    picks = DiscoverBooksUiState.Ready(many),
                                    selection = LeaderboardSelection(LeaderboardPeriod.Year, LeaderboardCategory.Time),
                                    recentlyAdded = RecentlyAddedUiState.Ready(emptyList()),
                                ),
                                noActions(),
                                { it() },
                                PaddingValues(),
                                false,
                                emptySet(),
                                listState,
                            )
                        }
                    }
                }
            }
        }
        composeRule.runOnIdle { landings.request(ShellLanding.DiscoverLeaderboard) }
        composeRule.waitForIdle()

        assertEquals(listOf(LeaderboardPeriod.Week), periods)
        composeRule.onNode(hasText("Leaderboard") and isHeading()).assertIsDisplayed()
    }
}
