package com.calypsan.listenup.client.features.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.client.domain.DayBucket
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.features.home.components.HOME_STATS_CHART_TAG
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import io.kotest.matchers.shouldBe
import androidx.compose.ui.text.style.TextDirection
import com.calypsan.listenup.client.design.text.RightToLeft
import com.calypsan.listenup.client.design.text.textDirection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Spec §2.6 on Android: one streak rule, a chart with words, the leaderboard one tap away. */
@RunWith(RobolectricTestRunner::class)
class HomeStatsCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun week(
        current: Int,
        best: Int,
    ) = HomeStatsUiState.Data(
        totalSecondsThisWeek = 29_520L,
        currentStreakDays = current,
        longestStreakDays = best,
        dailyBuckets = listOf(4_800L, 5_520L, 0L, 1_500L, 6_000L, 7_500L, 4_200L).mapIndexed { i, s -> DayBucket(i, s) },
        topGenres = listOf(GenreShare("Fantasy", 6_000L), GenreShare("History", 4_000L)),
    )

    private fun show(
        stats: HomeStatsUiState,
        onOpenLeaderboard: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) = composeRule.setContent {
        MaterialTheme { HomeStatsCard(stats, isWide = false, onOpenLeaderboard = onOpenLeaderboard, onRetry = onRetry) }
    }

    @Test
    fun `a live streak is a chip, and the best is in the footer`() {
        show(week(current = 5, best = 12))
        composeRule.onNodeWithText("Your last 7 days").assertIsDisplayed()
        composeRule.onNodeWithText("5-day streak").assertIsDisplayed()
        composeRule.onNodeWithText("Best streak 12 days").assertIsDisplayed()
    }

    @Test
    fun `in a right-to-left layout the streak reads 1-day streak, not day streak-1`() {
        composeRule.setContent {
            RightToLeft {
                HomeStatsCard(week(current = 1, best = 1), isWide = false, onOpenLeaderboard = {}, onRetry = {})
            }
        }
        composeRule.onNodeWithText("1-day streak").textDirection() shouldBe TextDirection.Content
    }

    @Test
    fun `a lapsed streak shows only the best, never a 0-day streak`() {
        show(week(current = 0, best = 12))
        composeRule.onAllNodesWithText("0-day streak").assertCountEquals(0)
        composeRule.onAllNodesWithText("-day streak", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Best streak 12 days").assertIsDisplayed()
    }

    @Test
    fun `no streak at all says nothing about streaks`() {
        show(week(current = 0, best = 0))
        composeRule.onAllNodesWithText("streak", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a one-day best reads in the singular`() {
        show(week(current = 1, best = 1))
        composeRule.onNodeWithText("1-day streak").assertIsDisplayed()
        composeRule.onNodeWithText("Best streak 1 day").assertIsDisplayed()
    }

    @Test
    fun `the chart reads every day, oldest first, ending with today`() {
        show(week(current = 5, best = 12))
        composeRule.onNodeWithContentDescription("Listening per day, oldest to today:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("today 1h 20m", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a week under an hour totals in minutes alone, never 0h`() {
        show(week(current = 1, best = 1).copy(dailyBuckets = listOf(2_400L, 0L, 0L, 0L, 0L, 0L, 0L).mapIndexed(::DayBucket)))
        composeRule.onNodeWithText("40m").assertIsDisplayed()
        composeRule.onAllNodesWithText("0h", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a week of an hour or more totals in hours and minutes`() {
        show(week(current = 5, best = 12))
        composeRule.onNodeWithText("8h 12m").assertIsDisplayed()
    }

    @Test
    fun `Leaderboard leaves for Discover`() {
        var opened = false
        show(week(current = 5, best = 12), onOpenLeaderboard = { opened = true })
        composeRule.onNodeWithText("Leaderboard").performClick()
        opened shouldBe true
    }

    @Test
    fun `a new listener sees a one-line invitation, never a zero chart`() {
        show(HomeStatsUiState.Empty)
        composeRule.onNodeWithText("Your last 7 days").assertIsDisplayed()
        composeRule
            .onNodeWithText("Your listening time, streak and favourite genres fill in here after your first book.")
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag(HOME_STATS_CHART_TAG).assertCountEquals(0)
    }

    @Test
    fun `an error offers a Try again button that refreshes the stats`() {
        var retried = false
        show(HomeStatsUiState.Error(isRetryable = true), onRetry = { retried = true })
        composeRule.onNodeWithText("Couldn't load your stats.").assertIsDisplayed()
        composeRule.onNode(hasText("Try again") and hasClickAction()).performClick()
        retried shouldBe true
    }

    @Test
    fun `loading is a skeleton the size of the data card`() {
        show(HomeStatsUiState.Loading)
        composeRule.onNodeWithTag(HOME_STATS_SKELETON_TAG).assertIsDisplayed()
    }
}
