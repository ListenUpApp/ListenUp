package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ScoreSource
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The per-source breakdown sheet one tap away from the Book Detail headline. */
@RunWith(RobolectricTestRunner::class)
class RatingBreakdownSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `one row per source, highest rating count first`() {
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown =
                    listOf(
                        ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.5, count = 8_100),
                        ExternalRating(source = ExternalRatingSource.HARDCOVER, average = 4.0, count = 300),
                    ),
                canRefresh = false,
                isRefreshingExternal = false,
                onRefresh = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Audible · 4.5 · 8.1k").assertIsDisplayed()
        composeRule.onNodeWithText("Hardcover · 4.0 · 300").assertIsDisplayed()
    }

    @Test
    fun `no refresh action when the signed-in listener cannot refresh`() {
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown = emptyList(),
                canRefresh = false,
                isRefreshingExternal = false,
                onRefresh = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Refresh ratings").assertDoesNotExist()
    }

    @Test
    fun `refresh action is shown when canRefresh and calls the view model`() {
        var refreshed = false
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown = listOf(ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.5, count = 8_100)),
                canRefresh = true,
                isRefreshingExternal = false,
                onRefresh = { refreshed = true },
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Refresh ratings").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh ratings").performClick()

        refreshed shouldBe true
    }

    @Test
    fun `the refresh button is busy and disabled while a refresh is in flight`() {
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown = listOf(ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.5, count = 8_100)),
                canRefresh = true,
                isRefreshingExternal = true,
                onRefresh = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithTag("refreshRatingsButton").assertIsNotEnabled()
        composeRule.onNodeWithText("Refresh ratings").assertDoesNotExist()
    }

    // --- What each source contributes to the ListenUp score ---

    private val audible = ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.7, count = 1_007)
    private val goodreads = ExternalRating(source = ExternalRatingSource.GOODREADS, average = 4.2, count = 100_000)

    @Test
    fun `each outside row shows its share of the score, to a whole percent`() {
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown = listOf(goodreads, audible),
                score =
                    CombinedScore(
                        average = 4.3,
                        count = 101_007,
                        shares =
                            mapOf(
                                ScoreSource.Outside(ExternalRatingSource.GOODREADS) to 0.6249,
                                ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 0.3751,
                            ),
                    ),
                canRefresh = false,
                isRefreshingExternal = false,
                onRefresh = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Goodreads · 4.2 · 100k · 62%").assertIsDisplayed()
        composeRule.onNodeWithText("Audible · 4.7 · 1k · 38%").assertIsDisplayed()
    }

    @Test
    fun `a score from several sources says how many`() {
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown = listOf(goodreads, audible),
                score =
                    CombinedScore(
                        average = 4.3,
                        count = 101_007,
                        shares =
                            mapOf(
                                ScoreSource.Outside(ExternalRatingSource.GOODREADS) to 0.6,
                                ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 0.4,
                            ),
                    ),
                canRefresh = false,
                isRefreshingExternal = false,
                onRefresh = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Combined from 2 sources").assertIsDisplayed()
    }

    @Test
    fun `a score from one source does not say combined`() {
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown = listOf(audible),
                score =
                    CombinedScore(
                        average = 4.4,
                        count = 1_007,
                        shares = mapOf(ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 1.0),
                    ),
                canRefresh = false,
                isRefreshingExternal = false,
                onRefresh = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Combined from", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Audible · 4.7 · 1k · 100%").assertIsDisplayed()
    }

    @Test
    fun `your listeners get their own row when they are part of the score`() {
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown = listOf(audible),
                score =
                    CombinedScore(
                        average = 4.4,
                        count = 1_010,
                        shares =
                            mapOf(
                                ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 0.8,
                                ScoreSource.Listeners to 0.2,
                            ),
                    ),
                listeners = ListenerAverage(averageHalfStars = 8.0, count = 3),
                canRefresh = false,
                isRefreshingExternal = false,
                onRefresh = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Combined from 2 sources").assertIsDisplayed()
        composeRule.onNodeWithText("Audible · 4.7 · 1k · 80%").assertIsDisplayed()
        composeRule.onNodeWithText("Your listeners · 4.0 · 3 · 20%").assertIsDisplayed()
    }

    @Test
    fun `no listeners row when no listener has rated the book`() {
        composeRule.setContent {
            RatingBreakdownSheet(
                breakdown = listOf(audible),
                score =
                    CombinedScore(
                        average = 4.4,
                        count = 1_007,
                        shares = mapOf(ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 1.0),
                    ),
                listeners = null,
                canRefresh = false,
                isRefreshingExternal = false,
                onRefresh = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Your listeners", substring = true).assertDoesNotExist()
    }
}
