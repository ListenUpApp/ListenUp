package com.calypsan.listenup.client.features.discover.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import com.calypsan.listenup.client.features.discover.WithAvatarDependencies
import com.calypsan.listenup.client.features.discover.entry
import com.calypsan.listenup.client.presentation.discover.CommunitySize
import com.calypsan.listenup.client.presentation.discover.LeaderboardSelection
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spec §3.8 for the leaderboard: the period chips stay in every state, a period nobody listened in
 * says so, only you hides the category (all three would rank you first) and closes with "Just you so
 * far", loading is a skeleton of rows (also while the roster is unknown), and a failure has its own
 * Try again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w412dp-h3000dp")
class LeaderboardStatesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun stopGlobalKoin() {
        stopKoin()
    }

    private fun show(
        state: LeaderboardUiState,
        community: CommunitySize = CommunitySize.Others,
        onRetry: () -> Unit = {},
    ) = composeRule.setContent {
        WithAvatarDependencies {
            DiscoverLeaderboardSection(
                state = state,
                selection = LeaderboardSelection(LeaderboardPeriod.Week, LeaderboardCategory.Time),
                community = community,
                onSelectPeriod = {},
                onSelectCategory = {},
                onUserClick = {},
                onRetry = onRetry,
            )
        }
    }

    private fun onlyYou() =
        LeaderboardUiState.Data(
            LeaderboardSnapshot(
                time = listOf(entry(1, "me", "Simon", 29_520, isYou = true)),
                books = emptyList(),
                streak = emptyList(),
            ),
        )

    @Test
    fun `the chips stay in every state, and loading is a skeleton of rows`() {
        show(LeaderboardUiState.Loading)
        composeRule.onAllNodes(hasText("Week") and hasClickAction()).assertCountEquals(1)
        composeRule.onNodeWithTag("leaderboard-skeleton").assertIsDisplayed()
    }

    @Test
    fun `the loading rows are the real rows' height and gaps`() {
        show(LeaderboardUiState.Loading)
        // LeaderboardList: five 56dp rows 2dp apart, each led by a 36dp rank badge before the avatar.
        composeRule.onNodeWithTag("leaderboard-skeleton").assertHeightIsEqualTo(5 * 56.dp + 4 * 2.dp)
    }

    @Test
    fun `a period nobody listened in says so under the chips, with the category still there`() {
        show(LeaderboardUiState.Empty, community = CommunitySize.Others)
        composeRule.onNodeWithText("Nobody listened in this period yet.").assertIsDisplayed()
        composeRule.onNodeWithText("Books").assertIsDisplayed()
    }

    @Test
    fun `an empty board before the roster has synced is a skeleton, never a claim`() {
        show(LeaderboardUiState.Empty, community = CommunitySize.Unknown)
        composeRule.onNodeWithTag("leaderboard-skeleton").assertIsDisplayed()
        composeRule.onNodeWithText("Nobody listened in this period yet.").assertDoesNotExist()
        composeRule.onNodeWithText("Just you so far", substring = true).assertDoesNotExist()
    }

    @Test
    fun `only you hides the category and reads Just you so far under your row`() {
        show(onlyYou(), community = CommunitySize.JustYou)
        composeRule.onAllNodes(hasText("Week") and hasClickAction()).assertCountEquals(1)
        composeRule.onNodeWithText("Books").assertDoesNotExist()
        composeRule.onNodeWithText("You").assertIsDisplayed()
        composeRule.onNodeWithText("Just you so far. The board fills in as people join.").assertIsDisplayed()
    }

    @Test
    fun `only you with no time this period is the line alone (Q2)`() {
        show(LeaderboardUiState.Empty, community = CommunitySize.JustYou)
        composeRule.onNodeWithText("Just you so far. The board fills in as people join.").assertIsDisplayed()
        composeRule.onNodeWithText("Nobody listened in this period yet.").assertDoesNotExist()
        composeRule.onNodeWithText("Books").assertDoesNotExist()
    }

    @Test
    fun `an error offers Try again`() {
        var retried = 0
        show(LeaderboardUiState.Error(UnexpectedClientError()), onRetry = { retried++ })
        composeRule.onNodeWithText("Couldn't load the leaderboard").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }
}
